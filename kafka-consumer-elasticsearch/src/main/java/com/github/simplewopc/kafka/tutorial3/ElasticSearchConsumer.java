package com.github.simplewopc.kafka.tutorial3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

public class ElasticSearchConsumer {
    static Logger logger = LoggerFactory.getLogger(ElasticSearchConsumer.class);

    public static void main(String[] args) throws IOException {

        RestHighLevelClient client = createClient();
        KafkaConsumer<String, String> consumer = createConsumer("twitter_tweets");

        while(true) {
            ConsumerRecords<String, String> records =
                    consumer.poll(Duration.ofMillis(100));

            int recordsCount = records.count();

            logger.info("Received: " + recordsCount + " records.");

            BulkRequest bulkRequest = new BulkRequest();

            for (ConsumerRecord<String, String> record: records) {

                String id = extractIdFromTweet(record.value());

                if(id == null)
                    continue;

                // insert data into ElasticSearch
                IndexRequest indexRequest = new IndexRequest("twitter")
                        .source(record.value(), XContentType.JSON)
                        .id(id); // unique ID makes the consumer idempotent

                bulkRequest.add(indexRequest); // adding our bulk request (takes no time)
            }

            if (recordsCount > 0) {
                BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);
                logger.info("Committing offsets...");
                consumer.commitSync();
                logger.info("Offsets have been committed");

                sleep(1000);
            }
        }

        // close the client gracefully
        //client.close();
    }

    public static KafkaConsumer<String, String> createConsumer(String topic){

        String bootstrapServers =  "127.0.0.1:9092";
        String groupId = "kafka-demo-elasticsearch";
        Properties properties = getProperties(bootstrapServers, groupId);

        // create consumer
        KafkaConsumer<String, String> consumer = new KafkaConsumer<String, String>(properties);
        consumer.subscribe(Arrays.asList(topic));

        return consumer;
    }

    public static RestHighLevelClient createClient() {

        String hostName = "kafka-course-2575327868.us-east-1.bonsaisearch.net";
        String userName = "8849wnhhv7";
        String password = "w7odxu9fx4";

        // don't do if you run a local ES
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(userName, password));

        RestClientBuilder builder = RestClient.builder(
                new HttpHost(hostName, 443, "https"))
                .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                    @Override
                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpAsyncClientBuilder) {
                        return httpAsyncClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    }
                });
        RestHighLevelClient client = new RestHighLevelClient(builder);
        return client;

    }

    private static String extractIdFromTweet(String tweetJson){
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNodeRoot = null;
        try {
            jsonNodeRoot = objectMapper.readTree(tweetJson);
            JsonNode jsonNode = jsonNodeRoot.get("id_str");
            return jsonNode.asText();
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static Properties getProperties(String bootstrapServers, String groupId) {
        Properties properties = new Properties();

        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // disable auto commit of offset
        properties.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
        return properties;
    }

    private static void sleep(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
