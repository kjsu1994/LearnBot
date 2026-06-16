package com.learnbot.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "learnbot")
public class LearnBotProperties {
    private Crawler crawler = new Crawler();
    private Chunking chunking = new Chunking();
    private Embedding embedding = new Embedding();
    private Ollama ollama = new Ollama();
    private Rag rag = new Rag();
    private Storage storage = new Storage();
    private Code code = new Code();

    public Crawler getCrawler() {
        return crawler;
    }

    public void setCrawler(Crawler crawler) {
        this.crawler = crawler;
    }

    public Chunking getChunking() {
        return chunking;
    }

    public void setChunking(Chunking chunking) {
        this.chunking = chunking;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    public Ollama getOllama() {
        return ollama;
    }

    public void setOllama(Ollama ollama) {
        this.ollama = ollama;
    }

    public Rag getRag() {
        return rag;
    }

    public void setRag(Rag rag) {
        this.rag = rag;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Code getCode() {
        return code;
    }

    public void setCode(Code code) {
        this.code = code;
    }

    public static class Crawler {
        private List<String> allowedDomains = new ArrayList<>(List.of("example.com"));

        @Min(1)
        private int timeoutSeconds = 15;

        @Min(0)
        private long rateLimitMillis = 1000;

        @Min(1)
        private int maxPagesPerRequest = 1;

        private boolean respectRobotsTxt = true;

        public List<String> getAllowedDomains() {
            return allowedDomains;
        }

        public void setAllowedDomains(List<String> allowedDomains) {
            this.allowedDomains = allowedDomains;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public long getRateLimitMillis() {
            return rateLimitMillis;
        }

        public void setRateLimitMillis(long rateLimitMillis) {
            this.rateLimitMillis = rateLimitMillis;
        }

        public int getMaxPagesPerRequest() {
            return maxPagesPerRequest;
        }

        public void setMaxPagesPerRequest(int maxPagesPerRequest) {
            this.maxPagesPerRequest = maxPagesPerRequest;
        }

        public boolean isRespectRobotsTxt() {
            return respectRobotsTxt;
        }

        public void setRespectRobotsTxt(boolean respectRobotsTxt) {
            this.respectRobotsTxt = respectRobotsTxt;
        }
    }

    public static class Chunking {
        @Min(200)
        private int size = 1200;

        @Min(0)
        private int overlap = 150;

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public int getOverlap() {
            return overlap;
        }

        public void setOverlap(int overlap) {
            this.overlap = overlap;
        }
    }

    public static class Embedding {
        @Min(1)
        private int dimensions = 1024;

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }
    }

    public static class Ollama {
        @NotBlank
        private String baseUrl = "http://localhost:11434";

        @NotBlank
        private String chatModel = "gemma4:e2b";

        @NotBlank
        private String embeddingModel = "bge-m3";

        private double temperature = 0.2;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }
    }

    public static class Rag {
        @Min(1)
        private int topK = 6;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }
    }

    public static class Storage {
        @NotBlank
        private String endpoint = "http://localhost:9000";

        @NotBlank
        private String accessKey = "learnbot";

        @NotBlank
        private String secretKey = "learnbot1234";

        @NotBlank
        private String bucket = "learnbot-raw";

        @NotBlank
        private String region = "us-east-1";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }

    public static class Code {
        @NotBlank
        private String workspacePath = "/var/lib/learnbot/repos";

        @Min(1)
        private long maxFileBytes = 1_000_000;

        @Min(1)
        private int maxFiles = 5000;

        @Min(1)
        private int topK = 10;

        public String getWorkspacePath() {
            return workspacePath;
        }

        public void setWorkspacePath(String workspacePath) {
            this.workspacePath = workspacePath;
        }

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }

        public int getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(int maxFiles) {
            this.maxFiles = maxFiles;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }
    }
}
