package io.confluent.ps.abraham.leal.processors;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mongodb.WriteConcern;
import com.mongodb.client.*;
import com.mongodb.client.result.InsertOneResult;
import io.confluent.gen.ServingRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.log4j.Logger;
import org.bson.Document;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

public class writeToMongo implements Processor<String, ServingRecord> {
    // This is an example of a transformer that queries mongoDB and
    // returns a new value with the processed result
    private MongoClient mongoClient;
    private MongoDatabase mongoDb;
    private KafkaProducer<String,ServingRecord> dlqProducer;
    private final Logger logger = Logger.getLogger(writeToMongo.class);
    private final String connectionString = System.getenv("MongoConnectionString");
    private final String dbName = System.getenv("MongoDBName");
    private final String collection = System.getenv("MongoCollection");
    private final String dlqTopic = System.getenv("dlqTopic");
    @Override
    public void init(ProcessorContext context) {
        // Initialize MongoDB client
        mongoClient = MongoClients.create(connectionString);
        logger.info("Opened connection to MongoDB");

        //Setting up a producer for DLQing
        dlqProducer = new KafkaProducer<>(context.appConfigs());

        // Get connection to specific DB, this assumes info from a single DB,
        // but multiple DB clients can be instantiated
        logger.info("Got database with name: " + dbName);
        mongoDb = mongoClient.getDatabase(dbName);
    }

    @Override
    public void process(String key, ServingRecord value) {
        //Generating our Document to write from the value of the kafka record
        Gson gson = new GsonBuilder().create();
        String valueJSON = gson.toJson(value);
        JSONObject jsonObj = new JSONObject(valueJSON);
        Document toWrite = Document.parse(jsonObj.toString());

        // We are setting a global restraint: Only consider a write successful if the
        // Write is in all journals of a Majority of MongoDB replicas
        // With a timeout for answer of 500 milliseconds
        WriteConcern guarantee = WriteConcern.ACKNOWLEDGED
                .withJournal(true)
                .withWTimeout(500, TimeUnit.MILLISECONDS)
                .withW("majority");

        MongoCollection<Document> collectionToWrite = mongoDb.getCollection(collection).withWriteConcern(guarantee);
        boolean isWritten = false;

        try {
            InsertOneResult isWrittenDoc = collectionToWrite.insertOne(toWrite);
            isWritten = isWrittenDoc.wasAcknowledged();
        }catch(com.mongodb.MongoWriteException w){
            logger.info("Failed to write to MongoDB, most likely due to not being able to establish a " +
                    "good connection to the database and/or collection");
            logger.info("Sending record to DLQ...");
            ProducerRecord<String,ServingRecord> toSend =
                    new ProducerRecord<String, ServingRecord>(dlqTopic,key,value);
            toSend.headers().add("stacktrace",w.toString().getBytes());
            toSend.headers().add("timestamp",String.valueOf(System.currentTimeMillis()).getBytes());
            dlqProducer.send(toSend);
        }catch(com.mongodb.WriteConcernException c){
            logger.info("Write to MongoDB exceeded the timeout for acknowledging the write to a majority of" +
                    " replicas, the write may still be successful, but MongoDB failed to acknowledge it.");
            logger.info("Sending record to DLQ...");
            ProducerRecord<String,ServingRecord> toSend =
                    new ProducerRecord<String, ServingRecord>(dlqTopic,key,value);
            toSend.headers().add("stacktrace",c.toString().getBytes());
            dlqProducer.send(toSend);
        }catch(com.mongodb.MongoException e){
            logger.info("Failed to write to MongoDB for unknown reasons.");
            e.printStackTrace();
        }

        if(isWritten){
            logger.debug("Successfully written a message to Mongo DB with key: " + key);
        }
    }

    @Override
    public void close() {
        // Close out mongo connection when finished processing
        mongoClient.close();
        dlqProducer.flush();
        dlqProducer.close();
    }
}
