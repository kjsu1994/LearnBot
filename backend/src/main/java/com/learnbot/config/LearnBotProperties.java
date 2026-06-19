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
    private Document document = new Document();
    private Auth auth = new Auth();
    private Transfer transfer = new Transfer();

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

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public Transfer getTransfer() {
        return transfer;
    }

    public void setTransfer(Transfer transfer) {
        this.transfer = transfer;
    }

    public static class Crawler {
        private List<String> allowedDomains = new ArrayList<>(List.of("example.com"));

        @Min(1)
        private int timeoutSeconds = 15;

        @Min(0)
        private long rateLimitMillis = 1000;

        @Min(1)
        private int maxPagesPerRequest = 30;

        @Min(0)
        private int maxDepth = 2;

        @Min(0)
        private int minContentChars = 200;

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

        public int getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
        }

        public int getMinContentChars() {
            return minContentChars;
        }

        public void setMinContentChars(int minContentChars) {
            this.minContentChars = minContentChars;
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

        @Min(1)
        private int batchSize = 64;

        @Min(1)
        private int minBatchSize = 8;

        @Min(1)
        private int insertBatchSize = 200;

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMinBatchSize() {
            return minBatchSize;
        }

        public void setMinBatchSize(int minBatchSize) {
            this.minBatchSize = minBatchSize;
        }

        public int getInsertBatchSize() {
            return insertBatchSize;
        }

        public void setInsertBatchSize(int insertBatchSize) {
            this.insertBatchSize = insertBatchSize;
        }
    }

    public static class Ollama {
        @NotBlank
        private String baseUrl = "http://localhost:11434";

        @NotBlank
        private String chatModel = "qwen3:8b-q4_K_M";

        @NotBlank
        private String primaryChatModel = "qwen3:8b-q4_K_M";

        @NotBlank
        private String auxiliaryChatModel = "qwen3.5:2b-q4_K_M";

        @NotBlank
        private String embeddingModel = "bge-m3";

        private double temperature = 0.2;

        @Min(512)
        private int contextWindow = 4096;

        @Min(0)
        private int maxOutputTokens = 0;

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

        public String getPrimaryChatModel() {
            return primaryChatModel;
        }

        public void setPrimaryChatModel(String primaryChatModel) {
            this.primaryChatModel = primaryChatModel;
        }

        public String getAuxiliaryChatModel() {
            return auxiliaryChatModel;
        }

        public void setAuxiliaryChatModel(String auxiliaryChatModel) {
            this.auxiliaryChatModel = auxiliaryChatModel;
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

        public int getContextWindow() {
            return contextWindow;
        }

        public void setContextWindow(int contextWindow) {
            this.contextWindow = contextWindow;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }
    }

    public static class Rag {
        @Min(1)
        private int topK = 6;

        private Pipeline pipeline = new Pipeline();
        private DocumentContext documentContext = new DocumentContext();
        private Overview overview = new Overview();

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public Pipeline getPipeline() {
            return pipeline;
        }

        public void setPipeline(Pipeline pipeline) {
            this.pipeline = pipeline;
        }

        public DocumentContext getDocumentContext() {
            return documentContext;
        }

        public void setDocumentContext(DocumentContext documentContext) {
            this.documentContext = documentContext;
        }

        public Overview getOverview() {
            return overview;
        }

        public void setOverview(Overview overview) {
            this.overview = overview;
        }

        public static class Pipeline {
            private boolean rewriteEnabled = true;
            private boolean selfCheckEnabled = true;

            @Min(1)
            private int maxIterations = 2;

            @Min(1)
            private int rerankTopN = 30;

            @Min(1)
            private int documentContextLimit = 8;

            @Min(1)
            private int codeContextLimit = 8;

            private double minTopScore = 0.30;
            private double minCoverage = 0.15;

            public boolean isRewriteEnabled() {
                return rewriteEnabled;
            }

            public void setRewriteEnabled(boolean rewriteEnabled) {
                this.rewriteEnabled = rewriteEnabled;
            }

            public boolean isSelfCheckEnabled() {
                return selfCheckEnabled;
            }

            public void setSelfCheckEnabled(boolean selfCheckEnabled) {
                this.selfCheckEnabled = selfCheckEnabled;
            }

            public int getMaxIterations() {
                return maxIterations;
            }

            public void setMaxIterations(int maxIterations) {
                this.maxIterations = maxIterations;
            }

            public int getRerankTopN() {
                return rerankTopN;
            }

            public void setRerankTopN(int rerankTopN) {
                this.rerankTopN = rerankTopN;
            }

            public int getDocumentContextLimit() {
                return documentContextLimit;
            }

            public void setDocumentContextLimit(int documentContextLimit) {
                this.documentContextLimit = documentContextLimit;
            }

            public int getCodeContextLimit() {
                return codeContextLimit;
            }

            public void setCodeContextLimit(int codeContextLimit) {
                this.codeContextLimit = codeContextLimit;
            }

            public double getMinTopScore() {
                return minTopScore;
            }

            public void setMinTopScore(double minTopScore) {
                this.minTopScore = minTopScore;
            }

            public double getMinCoverage() {
                return minCoverage;
            }

            public void setMinCoverage(double minCoverage) {
                this.minCoverage = minCoverage;
            }
        }

        public static class DocumentContext {
            private boolean enabled = true;
            private boolean llmSummaryEnabled = true;

            @Min(1)
            private int maxSourceDocuments = 80;

            @Min(1000)
            private int maxSummaryInputChars = 9000;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public boolean isLlmSummaryEnabled() {
                return llmSummaryEnabled;
            }

            public void setLlmSummaryEnabled(boolean llmSummaryEnabled) {
                this.llmSummaryEnabled = llmSummaryEnabled;
            }

            public int getMaxSourceDocuments() {
                return maxSourceDocuments;
            }

            public void setMaxSourceDocuments(int maxSourceDocuments) {
                this.maxSourceDocuments = maxSourceDocuments;
            }

            public int getMaxSummaryInputChars() {
                return maxSummaryInputChars;
            }

            public void setMaxSummaryInputChars(int maxSummaryInputChars) {
                this.maxSummaryInputChars = maxSummaryInputChars;
            }
        }

        public static class Overview {
            private boolean enabled = true;

            @Min(1)
            private int minContextChunks = 2;

            @Min(1)
            private int minOriginalChunks = 4;

            @Min(1)
            private int maxDocuments = 12;

            @Min(1)
            private int maxCodeCategories = 10;

            @Min(1)
            private int maxRecursiveIterations = 2;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getMinContextChunks() {
                return minContextChunks;
            }

            public void setMinContextChunks(int minContextChunks) {
                this.minContextChunks = minContextChunks;
            }

            public int getMinOriginalChunks() {
                return minOriginalChunks;
            }

            public void setMinOriginalChunks(int minOriginalChunks) {
                this.minOriginalChunks = minOriginalChunks;
            }

            public int getMaxDocuments() {
                return maxDocuments;
            }

            public void setMaxDocuments(int maxDocuments) {
                this.maxDocuments = maxDocuments;
            }

            public int getMaxCodeCategories() {
                return maxCodeCategories;
            }

            public void setMaxCodeCategories(int maxCodeCategories) {
                this.maxCodeCategories = maxCodeCategories;
            }

            public int getMaxRecursiveIterations() {
                return maxRecursiveIterations;
            }

            public void setMaxRecursiveIterations(int maxRecursiveIterations) {
                this.maxRecursiveIterations = maxRecursiveIterations;
            }
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
        private long maxArchiveBytes = 512_000_000;

        @Min(1)
        private long maxArchiveExtractedBytes = 1_000_000_000;

        @Min(1)
        private int topK = 10;

        @Min(1)
        private int indexThreads = 2;

        @NotBlank
        private String credentialSecret = "learnbot-local-dev-secret-change-me";

        private Context context = new Context();
        private Graph graph = new Graph();

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

        public long getMaxArchiveBytes() {
            return maxArchiveBytes;
        }

        public void setMaxArchiveBytes(long maxArchiveBytes) {
            this.maxArchiveBytes = maxArchiveBytes;
        }

        public long getMaxArchiveExtractedBytes() {
            return maxArchiveExtractedBytes;
        }

        public void setMaxArchiveExtractedBytes(long maxArchiveExtractedBytes) {
            this.maxArchiveExtractedBytes = maxArchiveExtractedBytes;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public int getIndexThreads() {
            return indexThreads;
        }

        public void setIndexThreads(int indexThreads) {
            this.indexThreads = indexThreads;
        }

        public String getCredentialSecret() {
            return credentialSecret;
        }

        public void setCredentialSecret(String credentialSecret) {
            this.credentialSecret = credentialSecret;
        }

        public Context getContext() {
            return context;
        }

        public void setContext(Context context) {
            this.context = context;
        }

        public Graph getGraph() {
            return graph;
        }

        public void setGraph(Graph graph) {
            this.graph = graph;
        }

        public static class Context {
            private boolean enabled = true;
            private boolean llmSummaryEnabled = true;

            @Min(1)
            private int maxFileSummaries = 1500;

            @Min(0)
            private int maxLlmDirectorySummaries = 12;

            @Min(1)
            private int maxTreeDepth = 4;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public boolean isLlmSummaryEnabled() {
                return llmSummaryEnabled;
            }

            public void setLlmSummaryEnabled(boolean llmSummaryEnabled) {
                this.llmSummaryEnabled = llmSummaryEnabled;
            }

            public int getMaxFileSummaries() {
                return maxFileSummaries;
            }

            public void setMaxFileSummaries(int maxFileSummaries) {
                this.maxFileSummaries = maxFileSummaries;
            }

            public int getMaxLlmDirectorySummaries() {
                return maxLlmDirectorySummaries;
            }

            public void setMaxLlmDirectorySummaries(int maxLlmDirectorySummaries) {
                this.maxLlmDirectorySummaries = maxLlmDirectorySummaries;
            }

            public int getMaxTreeDepth() {
                return maxTreeDepth;
            }

            public void setMaxTreeDepth(int maxTreeDepth) {
                this.maxTreeDepth = maxTreeDepth;
            }
        }

        public static class Graph {
            private boolean enabled = true;
            private boolean llmRelationEnabled = true;

            @Min(1)
            private int maxHop = 2;

            @Min(1)
            private int maxExpandedResults = 12;

            @Min(0)
            private int maxLlmFiles = 80;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public boolean isLlmRelationEnabled() {
                return llmRelationEnabled;
            }

            public void setLlmRelationEnabled(boolean llmRelationEnabled) {
                this.llmRelationEnabled = llmRelationEnabled;
            }

            public int getMaxHop() {
                return maxHop;
            }

            public void setMaxHop(int maxHop) {
                this.maxHop = maxHop;
            }

            public int getMaxExpandedResults() {
                return maxExpandedResults;
            }

            public void setMaxExpandedResults(int maxExpandedResults) {
                this.maxExpandedResults = maxExpandedResults;
            }

            public int getMaxLlmFiles() {
                return maxLlmFiles;
            }

            public void setMaxLlmFiles(int maxLlmFiles) {
                this.maxLlmFiles = maxLlmFiles;
            }
        }
    }

    public static class Document {
        @Min(1)
        private int indexThreads = 2;

        public int getIndexThreads() {
            return indexThreads;
        }

        public void setIndexThreads(int indexThreads) {
            this.indexThreads = indexThreads;
        }
    }

    public static class Auth {
        @NotBlank
        private String bootstrapAdminEmail = "admin@learnbot.local";

        @NotBlank
        private String bootstrapAdminPassword = "learnbot1234";

        @NotBlank
        private String bootstrapAdminName = "런봇 Admin";

        @Min(1)
        private int sessionHours = 12;

        public String getBootstrapAdminEmail() {
            return bootstrapAdminEmail;
        }

        public void setBootstrapAdminEmail(String bootstrapAdminEmail) {
            this.bootstrapAdminEmail = bootstrapAdminEmail;
        }

        public String getBootstrapAdminPassword() {
            return bootstrapAdminPassword;
        }

        public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
            this.bootstrapAdminPassword = bootstrapAdminPassword;
        }

        public String getBootstrapAdminName() {
            return bootstrapAdminName;
        }

        public void setBootstrapAdminName(String bootstrapAdminName) {
            this.bootstrapAdminName = bootstrapAdminName;
        }

        public int getSessionHours() {
            return sessionHours;
        }

        public void setSessionHours(int sessionHours) {
            this.sessionHours = sessionHours;
        }
    }

    public static class Transfer {
        @NotBlank
        private String exportDir = "export";

        public String getExportDir() {
            return exportDir;
        }

        public void setExportDir(String exportDir) {
            this.exportDir = exportDir;
        }
    }
}
