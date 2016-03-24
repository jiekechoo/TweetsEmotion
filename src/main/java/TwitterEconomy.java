import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.util.List;
import java.util.Properties;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.Time;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.receiver.Receiver;
import org.json.JSONException;
import org.json.JSONStringer;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations.SentimentAnnotatedTree;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.CoreMap;
import scala.Tuple2;
import twitter4j.FilterQuery;
import twitter4j.GeoLocation;
import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.StatusListener;
import twitter4j.TwitterStream;
import twitter4j.TwitterStreamFactory;
import twitter4j.auth.Authorization;
import twitter4j.auth.AuthorizationFactory;
import twitter4j.conf.ConfigurationBuilder;

/*
Class TwitterMood fetch the tweets streaming from twitter server, 
and use Spark Streaming platform, Stanford NLP toolkit to estimate 
the mood of each tweet.
*/

public class TwitterEconomy {

	/*
	 * Since the TwitterUtils provided by Spark Streaming doesn't support
	 * filtering tweets through location information. We customize our own
	 * Stream Listener.
	 */
	public static class CustomTwitterReceiver extends Receiver<Status> {
		/**
		 * 
		 */
		private static final long serialVersionUID = -759730515329151457L;
		Authorization twitterAuth = null;
		double[][] locationFilter = null;
		private boolean stopped = false;
		private TwitterStream twitterStream = null;

		public CustomTwitterReceiver(Authorization twitterAuth_, double[][] locationFilter_) {
			super(StorageLevel.MEMORY_AND_DISK_SER_2());
			twitterAuth = twitterAuth_;
			locationFilter = locationFilter_;
		}

		// implement the function to start a stream
		@Override
		public void onStart() {
			try {
				// get the twitter Auth token
				TwitterStream twitterStreamO = new TwitterStreamFactory().getInstance(twitterAuth);

				// implement our own listener for tweets stream
				twitterStreamO.addListener(new StatusListener() {

					// for each received tweet, we store it in Spark
					public void onStatus(Status status) {
						store(status);
					}

					public void onTrackLimitationNotice(int numberOfLimitedStatuses) {
					}

					public void onException(Exception ex) {
						if (!stopped) {
							restart("Error receiving tweets", ex);
						}
					}

					public void onScrubGeo(long userId, long upToStatusId) {
					}

					public void onDeletionNotice(StatusDeletionNotice statusDeletionNotice) {
					}

					public void onStallWarning(StallWarning arg0) {
					}
				});

				// build the filter
				FilterQuery fq = new FilterQuery();

				// ensure that the receiving tweets are from Europe
				fq.locations(locationFilter);
				twitterStreamO.filter(fq);
				setTwitterStream(twitterStreamO);
				stopped = false;
			} catch (Exception e) {
				restart("Error starting Twitter stream", e);
			}
		}

		// implement the method to stop the streaming
		@Override
		public void onStop() {
			stopped = true;
			twitterStream.shutdown();
		}

		// begin a new stream
		private void setTwitterStream(TwitterStream newTwitterStream) {
			if (twitterStream != null) {
				twitterStream.shutdown();
			}
			twitterStream = newTwitterStream;
		}
	}

	/*
	 * estimate the mood of each tweet, return a score for each tweet(0-4)
	 */
	public static int findSentiment(String line) {
		int mainSentiment = 0;

		// set up properties
		Properties props = new Properties();
		props.setProperty("annotators", "tokenize, ssplit, parse, sentiment");
		StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

		// estimate the mood score of each tweet
		if (line != null && line.length() > 0) {
			int longest = 0;
			Annotation annotation = pipeline.process(line);
			for (CoreMap sentence : annotation.get(CoreAnnotations.SentencesAnnotation.class)) {
				Tree tree = sentence.get(SentimentAnnotatedTree.class);
				int sentiment = RNNCoreAnnotations.getPredictedClass(tree);
				String partText = sentence.toString();
				if (partText.length() > longest) {
					mainSentiment = sentiment;
					longest = partText.length();
				}
			}
		}

		return mainSentiment;

	}

	public static void main(String[] args) {
		// TODO Auto-generated method stub

//		if (args.length < 5) {
//			System.out.println(
//					"Usage: java TwitterMood <consumerKey> <consumerSecret> <accessToken> <accessTokenSecret> <JSON outputpath>");
//
//			System.exit(1);
//		}

		// input your own consumerKey, consumerSecret, accessToken,
		// accessTokenSecret
		final String consumerKey = "JH8hAiA8hNtpVeJVBeKpsR2SS";
		final String consumerSecret = "IeLx2kSnWIgUSDkpumSEgoRZB34LjY5lJOwIoiI7yVD3ApLTiO";
		final String accessToken = "307668076-CaaLzuQnjsffu0qDcJ9bLxpmYA7M34QxvzMfk1hV";
		final String accessTokenSecret = "i0vMvy0mdvSRRTQpnMLUPiHAUteB3buwcaoFYChKvCs6F";

		final String[] colors = { "#66CCDD", "#8C99CC", "#B36699", "#D93366", "#FF0033" }; // from
																							// blue
																							// to
																							// red

		// get geoInfor
		final String url = "http://api.geonames.org/countryCode";
		final String params = "lat=%f&lng=%f&username=yhZheng";

		final String outPutFilePath = "/tmp/colorData/colors.json";

		final String[] countryCode = { "AD", "AL", "AM", "AT", "AZ", "BA", "BE", "BG", "BY", "CH", "CY", "CZ", "DE",
				"DK", "DZ", "EE", "ES", "FI", "FR", "GB", "GE", "GL", "GR", "HR", "HU", "IE", "IL", "IQ", "IR", "IS",
				"IT", "JO", "KZ", "LB", "LI", "LT", "LU", "LV", "MA", "MC", "MD", "ME", "MK", "MT", "NL", "NO", "PL",
				"PT", "RO", "RU", "SA", "SE", "SI", "SK", "SM", "SR", "SY", "TM", "TN", "TR", "UA" };

		final int countryNum = 61;

		// set up spark context
		SparkConf conf = new SparkConf().setMaster("local[4]").setAppName("TwitterEconomy");
		JavaStreamingContext jssc = new JavaStreamingContext(conf, new Duration(10000));

		// build twitter auth
		ConfigurationBuilder config = new ConfigurationBuilder();
		config.setOAuthConsumerKey(consumerKey);
		config.setOAuthConsumerSecret(consumerSecret);
		config.setOAuthAccessToken(accessToken);
		config.setOAuthAccessTokenSecret(accessTokenSecret);

		Authorization auth = AuthorizationFactory.getInstance(config.build());

		// bounding box of Europe
		double[][] locations = { { -31.266001d, 27.636311d }, { 39.869301d, 81.008797d } }; // Europe

		JavaDStream<Status> twitterStream = jssc.receiverStream(new CustomTwitterReceiver(auth, locations));

		// filtering the tweets without location information
		JavaDStream<Status> twitterFiltered = twitterStream.filter(new Function<Status, Boolean>() {
			public Boolean call(Status status) {
				if (status.getGeoLocation() != null) {
					return true;
				} else {
					return false;
				}
			}
		});

		// used to display information on the screen
		JavaDStream<String> statuses = twitterFiltered.map(new Function<Status, String>() {
			public String call(Status status) throws JSONException, FileNotFoundException {
				GeoLocation tweetLocation = status.getGeoLocation();

				String countryCode = HttpRequest.sendGet(url,
						String.format(params, tweetLocation.getLatitude(), tweetLocation.getLongitude()));

				int score = findSentiment(status.getText());

				return tweetLocation.toString() + " Country: " + countryCode.toLowerCase() + " Content: "
						+ status.getText() + " Score: " + score;
			}
		});

		// convert the JavaDStream to JavaPairDStream(<key, value>)
		JavaPairDStream<String, Tuple2<Integer, Integer>> singleStatusPair = twitterFiltered
				.mapToPair(new PairFunction<Status, String, Tuple2<Integer, Integer>>() {
					public Tuple2<String, Tuple2<Integer, Integer>> call(Status status) throws Exception {
						String countryCode = HttpRequest.sendGet(url, String.format(params,
								status.getGeoLocation().getLatitude(), status.getGeoLocation().getLongitude()));
						Integer score = findSentiment(status.getText());

						Tuple2<String, Tuple2<Integer, Integer>> result = new Tuple2<String, Tuple2<Integer, Integer>>(
								countryCode.toLowerCase(), new Tuple2<Integer, Integer>(score, 1));

						return result;
					}
				});

		// reduce by key and window
		JavaPairDStream<String, Tuple2<Integer, Integer>> WindowStatusReduce = singleStatusPair.reduceByKeyAndWindow(
				new Function2<Tuple2<Integer, Integer>, Tuple2<Integer, Integer>, Tuple2<Integer, Integer>>() {
					public Tuple2<Integer, Integer> call(Tuple2<Integer, Integer> t1, Tuple2<Integer, Integer> t2) {
						Tuple2<Integer, Integer> t3 = new Tuple2<Integer, Integer>(t1._1() + t2._1(),
								t1._2() + t2._2());

						return t3;
					}
				}, new Duration(30000), new Duration(10000));

		/*
		 * output the processing result for each RDD, output the result to JSON
		 * file for front-end program to display.
		 */
		WindowStatusReduce.foreach(new Function2<JavaPairRDD<String, Tuple2<Integer, Integer>>, Time, Void>() {
			public Void call(JavaPairRDD<String, Tuple2<Integer, Integer>> rdd, Time time) throws Exception {

				// output results
				List<Tuple2<String, Tuple2<Integer, Integer>>> listOfResults = rdd.collect();

				// build JSON String
				JSONStringer colorsJSON = new JSONStringer();
				colorsJSON.object();

				for (int i = 0; i < countryNum; i++) {
					boolean find = false;

					for (Tuple2<String, Tuple2<Integer, Integer>> item : listOfResults) {
						if (item._1().equals(countryCode[i].toLowerCase())) {
							int avgScore = (int) item._2()._1() / item._2()._2();

							colorsJSON.key(countryCode[i].toLowerCase()).value(colors[avgScore]);

							find = true;

							break;
						}
					}

					if (!find) {
						colorsJSON.key(countryCode[i].toLowerCase()).value("#FFFFFF");
					}
				}

				colorsJSON.endObject();

				// create file and write JSON String to file
				try {
					// output to file
					File colorsFile = new File(outPutFilePath);

					if (!colorsFile.exists()) {
						colorsFile.createNewFile();
					}

					FileWriter fw = new FileWriter(colorsFile);
					BufferedWriter outBuffer = new BufferedWriter(fw);
					outBuffer.write(colorsJSON.toString());
					outBuffer.flush();
					outBuffer.close();

				} catch (Exception e) {
					e.printStackTrace();
				}

				return null;
			}
		});

		// display programming information
		statuses.print();

		// set up checkpoint
		jssc.checkpoint("data/checkpoint");

		// start job
		jssc.start();

	}

}
