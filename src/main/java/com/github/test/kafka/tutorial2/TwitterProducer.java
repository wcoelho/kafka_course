package com.github.test.kafka.tutorial2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.twitter.hbc.ClientBuilder;
import com.twitter.hbc.core.Client;
import com.twitter.hbc.core.Constants;
import com.twitter.hbc.core.Hosts;
import com.twitter.hbc.core.HttpHosts;
import com.twitter.hbc.core.endpoint.StatusesFilterEndpoint;
import com.twitter.hbc.core.processor.StringDelimitedProcessor;
import com.twitter.hbc.httpclient.auth.Authentication;
import com.twitter.hbc.httpclient.auth.OAuth1;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TwitterProducer {
    Logger logger = LoggerFactory.getLogger(TwitterProducer.class);
    List<String> terms = Lists.newArrayList("Cruzeiro", "Potcker");
    String consumerKey = "MttnLifU1h2wJVFy3nHOls5Ep";
    String consumerSecret = "csEAXnkwtCnli8JEpgmpj95F7ANnkcuo2WxRLRH2zcZN4bu7cA";
    String token = "198128692-p5EDCV6V6NoYxHjrWETQ49X9kgs5xFDsNoYDvfi0";
    String secret = "93IYLEACDLDI0BKTOwv1678ZNgDgY5YVC5F2JDgrM7ke3";

    public TwitterProducer(){}

    public static void main(String[] args) throws JsonProcessingException {
        new TwitterProducer().run();
    }

    public void run() throws JsonProcessingException {

        logger.info("Setup");

        /** Set up your blocking queues: Be sure to size these properly based on expected TPS of your stream */
        BlockingQueue<String> msgQueue = new LinkedBlockingQueue<String>(1000);
        // create a twitter client
        Client client = createTwitterClient(msgQueue);
        // attempts to establish a connection
        client.connect();

        // create a kafka producer
        KafkaProducer<String,String> producer = createKafkaProducer();

        // add a shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Stopping application");
            logger.info("Shutting down client from Twitter...");
            client.stop();
            logger.info("Closing producer...");
            producer.close();
            logger.info("Application stopped");
        }));

        // loop to send tweets to kafka
        // on a different thread, or multiple different threads....
        while (!client.isDone()) {
            String msg = null;
            try {
                msg = msgQueue.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
                client.stop();
            }
            if(msg != null)
            {
                String finalMsg = mountMessage(msg);
                logger.info(finalMsg);
                producer.send(new ProducerRecord<>("twitter_tweets", null, finalMsg), new Callback() {
                    @Override
                    public void onCompletion(RecordMetadata recordMetadata, Exception e) {
                        if (e != null) {
                            logger.error("Something got wrong", e);
                        }
                    }
                });
            }
        }
        logger.info("End of application");

    }

    public Client createTwitterClient(BlockingQueue<String> msgQueue){
        /** Declare the host you want to connect to, the endpoint, and authentication (basic auth or oauth) */
        Hosts hosebirdHosts = new HttpHosts(Constants.STREAM_HOST);
        StatusesFilterEndpoint hosebirdEndpoint = new StatusesFilterEndpoint();

        hosebirdEndpoint.trackTerms(terms);

        // These secrets should be read from a config file
        Authentication hosebirdAuth = new OAuth1(consumerKey, consumerSecret, token, secret);

        ClientBuilder builder = new ClientBuilder()
                .name("Hosebird-Client-01")  // optional: mainly for the logs
                .hosts(hosebirdHosts)
                .authentication(hosebirdAuth)
                .endpoint(hosebirdEndpoint)
                .processor(new StringDelimitedProcessor(msgQueue));

        return builder.build();
    }

    public KafkaProducer<String, String> createKafkaProducer() {
        String bootstrapServers =  "127.0.0.1:9092";

        // create Producer properties
        Properties properties = new Properties();
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // create safe producer
        properties.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        properties.setProperty(ProducerConfig.ACKS_CONFIG, "all");
        properties.setProperty(ProducerConfig.RETRIES_CONFIG, Integer.toString(Integer.MAX_VALUE));
        properties.setProperty(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");

        // create the producer
        return new KafkaProducer<String, String>(properties);
    }

    private String mountMessage(String msg) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNodeRoot = objectMapper.readTree(msg);
        String message = getNodeValue(jsonNodeRoot, "text", null);
        String name = getNodeValue(jsonNodeRoot, "user", "name");
        return name + ": " + message;
    }

    private String getNodeValue(JsonNode rootNode, String nodeKey, String nodeKey2) {
        JsonNode jsonNodeInitial = rootNode.get(nodeKey);
        JsonNode finalJsonNode;
        if(nodeKey2!=null) {
            finalJsonNode = jsonNodeInitial.get(nodeKey2);
        } else {
            finalJsonNode = jsonNodeInitial;
        }
        return finalJsonNode.asText();
    }
}
