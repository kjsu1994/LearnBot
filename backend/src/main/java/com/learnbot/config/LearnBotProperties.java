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
    private Retention retention = new Retention();

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

    public Retention getRetention() {
        return retention;
    }

    public void setRetention(Retention retention) {
        this.retention = retention;
    }

    public static class Crawler {
        private List<String> allowedDomains = new ArrayList<>(List.of("example.com"));

        @Min(1)
        private int timeoutSeconds = 15;

        @Min(0)
        private long rateLimitMillis = 500;

        @Min(1)
        private int maxPagesPerRequest = 30;

        @Min(1)
        private int maxSitemapUrls = 500;

        @Min(0)
        private int maxDepth = 2;

        @Min(0)
        private int minContentChars = 80;

        private boolean respectRobotsTxt = true;

        private String crawlScope = "START_PATH";

        private String robotsFailurePolicy = "FAIL_CLOSED";

        private boolean includeAttachments = false;

        private boolean useSitemap = false;

        private String renderMode = "STATIC";

        @Min(1)
        private long maxAttachmentBytes = 25_000_000L;

        @Min(0)
        private int maxAttachmentsPerCrawl = 50;

        private boolean playwrightEnabled = false;

        @Min(1)
        private int playwrightTimeoutSeconds = 20;

        private String playwrightWaitUntil = "networkidle";

        @Min(0)
        private int playwrightMinStaticChars = 300;

        @Min(0)
        private int recursiveMinContentChars = 0;

        private double minTextDensity = 0.18;

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

        public int getMaxSitemapUrls() {
            return maxSitemapUrls;
        }

        public void setMaxSitemapUrls(int maxSitemapUrls) {
            this.maxSitemapUrls = maxSitemapUrls;
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

        public String getCrawlScope() {
            return crawlScope;
        }

        public void setCrawlScope(String crawlScope) {
            this.crawlScope = crawlScope;
        }

        public String getRobotsFailurePolicy() {
            return robotsFailurePolicy;
        }

        public void setRobotsFailurePolicy(String robotsFailurePolicy) {
            this.robotsFailurePolicy = robotsFailurePolicy;
        }

        public boolean isIncludeAttachments() {
            return includeAttachments;
        }

        public void setIncludeAttachments(boolean includeAttachments) {
            this.includeAttachments = includeAttachments;
        }

        public boolean isUseSitemap() {
            return useSitemap;
        }

        public void setUseSitemap(boolean useSitemap) {
            this.useSitemap = useSitemap;
        }

        public String getRenderMode() {
            return renderMode;
        }

        public void setRenderMode(String renderMode) {
            this.renderMode = renderMode;
        }

        public long getMaxAttachmentBytes() {
            return maxAttachmentBytes;
        }

        public void setMaxAttachmentBytes(long maxAttachmentBytes) {
            this.maxAttachmentBytes = maxAttachmentBytes;
        }

        public int getMaxAttachmentsPerCrawl() {
            return maxAttachmentsPerCrawl;
        }

        public void setMaxAttachmentsPerCrawl(int maxAttachmentsPerCrawl) {
            this.maxAttachmentsPerCrawl = maxAttachmentsPerCrawl;
        }

        public boolean isPlaywrightEnabled() {
            return playwrightEnabled;
        }

        public void setPlaywrightEnabled(boolean playwrightEnabled) {
            this.playwrightEnabled = playwrightEnabled;
        }

        public int getPlaywrightTimeoutSeconds() {
            return playwrightTimeoutSeconds;
        }

        public void setPlaywrightTimeoutSeconds(int playwrightTimeoutSeconds) {
            this.playwrightTimeoutSeconds = playwrightTimeoutSeconds;
        }

        public String getPlaywrightWaitUntil() {
            return playwrightWaitUntil;
        }

        public void setPlaywrightWaitUntil(String playwrightWaitUntil) {
            this.playwrightWaitUntil = playwrightWaitUntil;
        }

        public int getPlaywrightMinStaticChars() {
            return playwrightMinStaticChars;
        }

        public void setPlaywrightMinStaticChars(int playwrightMinStaticChars) {
            this.playwrightMinStaticChars = playwrightMinStaticChars;
        }

        public int getRecursiveMinContentChars() {
            return recursiveMinContentChars;
        }

        public void setRecursiveMinContentChars(int recursiveMinContentChars) {
            this.recursiveMinContentChars = recursiveMinContentChars;
        }

        public double getMinTextDensity() {
            return minTextDensity;
        }

        public void setMinTextDensity(double minTextDensity) {
            this.minTextDensity = minTextDensity;
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
            private int rerankTopN = 20;

            @Min(1)
            private int documentContextLimit = 8;

            @Min(1)
            private int codeContextLimit = 8;

            private boolean documentEvidenceRankingEnabled = true;
            private boolean documentAdjacentExpansionEnabled = true;

            @Min(0)
            private int documentAdjacentChunkRadius = 1;

            private double minTopScore = 0.30;
            private double minCoverage = 0.15;
            private String defaultDocumentSpeedProfile = "BALANCED";
            @Min(1)
            private int rewriteMaxOutputTokens = 192;

            @Min(1)
            private int rewriteTimeoutSeconds = 3;

            @Min(1)
            private int maxQueryCountBalanced = 2;

            @Min(512)
            private int promptTokenBudgetBalanced = 3500;

            private boolean answerRepairEnabled = true;
            private boolean queryEmbeddingCacheEnabled = true;

            @Min(1)
            private int queryEmbeddingCacheMaxEntries = 1024;

            @Min(1)
            private int queryEmbeddingCacheTtlSeconds = 3600;
            private Reranker reranker = new Reranker();

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

            public boolean isDocumentEvidenceRankingEnabled() {
                return documentEvidenceRankingEnabled;
            }

            public void setDocumentEvidenceRankingEnabled(boolean documentEvidenceRankingEnabled) {
                this.documentEvidenceRankingEnabled = documentEvidenceRankingEnabled;
            }

            public boolean isDocumentAdjacentExpansionEnabled() {
                return documentAdjacentExpansionEnabled;
            }

            public void setDocumentAdjacentExpansionEnabled(boolean documentAdjacentExpansionEnabled) {
                this.documentAdjacentExpansionEnabled = documentAdjacentExpansionEnabled;
            }

            public int getDocumentAdjacentChunkRadius() {
                return documentAdjacentChunkRadius;
            }

            public void setDocumentAdjacentChunkRadius(int documentAdjacentChunkRadius) {
                this.documentAdjacentChunkRadius = documentAdjacentChunkRadius;
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

            public String getDefaultDocumentSpeedProfile() {
                return defaultDocumentSpeedProfile;
            }

            public void setDefaultDocumentSpeedProfile(String defaultDocumentSpeedProfile) {
                this.defaultDocumentSpeedProfile = defaultDocumentSpeedProfile;
            }

            public int getRewriteMaxOutputTokens() {
                return rewriteMaxOutputTokens;
            }

            public void setRewriteMaxOutputTokens(int rewriteMaxOutputTokens) {
                this.rewriteMaxOutputTokens = rewriteMaxOutputTokens;
            }

            public int getRewriteTimeoutSeconds() {
                return rewriteTimeoutSeconds;
            }

            public void setRewriteTimeoutSeconds(int rewriteTimeoutSeconds) {
                this.rewriteTimeoutSeconds = rewriteTimeoutSeconds;
            }

            public int getMaxQueryCountBalanced() {
                return maxQueryCountBalanced;
            }

            public void setMaxQueryCountBalanced(int maxQueryCountBalanced) {
                this.maxQueryCountBalanced = maxQueryCountBalanced;
            }

            public int getPromptTokenBudgetBalanced() {
                return promptTokenBudgetBalanced;
            }

            public void setPromptTokenBudgetBalanced(int promptTokenBudgetBalanced) {
                this.promptTokenBudgetBalanced = promptTokenBudgetBalanced;
            }

            public boolean isAnswerRepairEnabled() {
                return answerRepairEnabled;
            }

            public void setAnswerRepairEnabled(boolean answerRepairEnabled) {
                this.answerRepairEnabled = answerRepairEnabled;
            }

            public boolean isQueryEmbeddingCacheEnabled() {
                return queryEmbeddingCacheEnabled;
            }

            public void setQueryEmbeddingCacheEnabled(boolean queryEmbeddingCacheEnabled) {
                this.queryEmbeddingCacheEnabled = queryEmbeddingCacheEnabled;
            }

            public int getQueryEmbeddingCacheMaxEntries() {
                return queryEmbeddingCacheMaxEntries;
            }

            public void setQueryEmbeddingCacheMaxEntries(int queryEmbeddingCacheMaxEntries) {
                this.queryEmbeddingCacheMaxEntries = queryEmbeddingCacheMaxEntries;
            }

            public int getQueryEmbeddingCacheTtlSeconds() {
                return queryEmbeddingCacheTtlSeconds;
            }

            public void setQueryEmbeddingCacheTtlSeconds(int queryEmbeddingCacheTtlSeconds) {
                this.queryEmbeddingCacheTtlSeconds = queryEmbeddingCacheTtlSeconds;
            }

            public Reranker getReranker() {
                return reranker;
            }

            public void setReranker(Reranker reranker) {
                this.reranker = reranker;
            }

            public static class Reranker {
                private boolean enabled = false;

                @NotBlank
                private String baseUrl = "http://localhost:8081";

                @Min(1)
                private int topN = 20;

                @Min(1)
                private int timeoutSeconds = 8;

                @Min(1)
                private int failureBackoffSeconds = 60;

                public boolean isEnabled() {
                    return enabled;
                }

                public void setEnabled(boolean enabled) {
                    this.enabled = enabled;
                }

                public String getBaseUrl() {
                    return baseUrl;
                }

                public void setBaseUrl(String baseUrl) {
                    this.baseUrl = baseUrl;
                }

                public int getTopN() {
                    return topN;
                }

                public void setTopN(int topN) {
                    this.topN = topN;
                }

                public int getTimeoutSeconds() {
                    return timeoutSeconds;
                }

                public void setTimeoutSeconds(int timeoutSeconds) {
                    this.timeoutSeconds = timeoutSeconds;
                }

                public int getFailureBackoffSeconds() {
                    return failureBackoffSeconds;
                }

                public void setFailureBackoffSeconds(int failureBackoffSeconds) {
                    this.failureBackoffSeconds = failureBackoffSeconds;
                }
            }
        }

        public static class DocumentContext {
            private boolean enabled = true;
            private boolean llmSummaryEnabled = true;

            @Min(1)
            private int maxSourceDocuments = 80;

            @Min(1000)
            private int maxSummaryInputChars = 9000;

            private boolean mapReduceEnabled = true;
            private boolean recursiveLlmSummaryEnabled = false;
            private boolean recursiveMapReduceEnabled = false;

            @Min(1)
            private int maxMapWindowsPerDocument = 8;
            @Min(1)
            private int recursiveMaxSourceDocuments = 40;

            @Min(1)
            private int mapWindowChunks = 6;

            @Min(1000)
            private int maxMapInputChars = 7000;

            @Min(1000)
            private int maxReduceInputChars = 12000;

            @Min(1)
            private int llmMaxOutputTokens = 384;

            @Min(1)
            private int llmReduceMaxOutputTokens = 512;

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

            public boolean isMapReduceEnabled() {
                return mapReduceEnabled;
            }

            public void setMapReduceEnabled(boolean mapReduceEnabled) {
                this.mapReduceEnabled = mapReduceEnabled;
            }

            public boolean isRecursiveLlmSummaryEnabled() {
                return recursiveLlmSummaryEnabled;
            }

            public void setRecursiveLlmSummaryEnabled(boolean recursiveLlmSummaryEnabled) {
                this.recursiveLlmSummaryEnabled = recursiveLlmSummaryEnabled;
            }

            public boolean isRecursiveMapReduceEnabled() {
                return recursiveMapReduceEnabled;
            }

            public void setRecursiveMapReduceEnabled(boolean recursiveMapReduceEnabled) {
                this.recursiveMapReduceEnabled = recursiveMapReduceEnabled;
            }

            public int getMaxMapWindowsPerDocument() {
                return maxMapWindowsPerDocument;
            }

            public void setMaxMapWindowsPerDocument(int maxMapWindowsPerDocument) {
                this.maxMapWindowsPerDocument = maxMapWindowsPerDocument;
            }

            public int getRecursiveMaxSourceDocuments() {
                return recursiveMaxSourceDocuments;
            }

            public void setRecursiveMaxSourceDocuments(int recursiveMaxSourceDocuments) {
                this.recursiveMaxSourceDocuments = recursiveMaxSourceDocuments;
            }

            public int getMapWindowChunks() {
                return mapWindowChunks;
            }

            public void setMapWindowChunks(int mapWindowChunks) {
                this.mapWindowChunks = mapWindowChunks;
            }

            public int getMaxMapInputChars() {
                return maxMapInputChars;
            }

            public void setMaxMapInputChars(int maxMapInputChars) {
                this.maxMapInputChars = maxMapInputChars;
            }

            public int getMaxReduceInputChars() {
                return maxReduceInputChars;
            }

            public void setMaxReduceInputChars(int maxReduceInputChars) {
                this.maxReduceInputChars = maxReduceInputChars;
            }

            public int getLlmMaxOutputTokens() {
                return llmMaxOutputTokens;
            }

            public void setLlmMaxOutputTokens(int llmMaxOutputTokens) {
                this.llmMaxOutputTokens = llmMaxOutputTokens;
            }

            public int getLlmReduceMaxOutputTokens() {
                return llmReduceMaxOutputTokens;
            }

            public void setLlmReduceMaxOutputTokens(int llmReduceMaxOutputTokens) {
                this.llmReduceMaxOutputTokens = llmReduceMaxOutputTokens;
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
            private String roslynAnalyzerPath = "/app/roslyn/LearnBot.RoslynAnalyzer.dll";
            private String roslynMode = "AUTO";

            @Min(1)
            private int roslynTimeoutSeconds = 120;

            private boolean dependencyResolutionEnabled = true;
            private List<String> dependencyAllowedRepositories = new ArrayList<>(List.of("https://repo.maven.apache.org/maven2"));

            @Min(1)
            private int dependencyMaxArtifacts = 256;

            @Min(1)
            private long dependencyMaxBytes = 536870912L;

            @Min(1)
            private int dependencyTimeoutSeconds = 120;

            @Min(1)
            private int maxHop = 2;

            @Min(1)
            private int maxExpandedResults = 12;

            @Min(1)
            private int maxSeedNodes = 24;

            @Min(1)
            private int maxEdgesPerNode = 12;

            @Min(1)
            private int maxCandidatesPerHop = 200;

            @Min(1)
            private int maxTraversalRows = 1000;

            @Min(0)
            private int maxLlmFiles = 80;

            @Min(1)
            private int enrichmentLeaseSeconds = 300;

            private boolean evidenceRankingEnabled = true;
            private boolean evidenceRankingDebug = false;

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

            public String getRoslynAnalyzerPath() {
                return roslynAnalyzerPath;
            }

            public void setRoslynAnalyzerPath(String roslynAnalyzerPath) {
                this.roslynAnalyzerPath = roslynAnalyzerPath;
            }

            public String getRoslynMode() { return roslynMode; }
            public void setRoslynMode(String roslynMode) { this.roslynMode = roslynMode; }
            public int getRoslynTimeoutSeconds() { return roslynTimeoutSeconds; }
            public void setRoslynTimeoutSeconds(int roslynTimeoutSeconds) { this.roslynTimeoutSeconds = roslynTimeoutSeconds; }
            public boolean isDependencyResolutionEnabled() { return dependencyResolutionEnabled; }
            public void setDependencyResolutionEnabled(boolean dependencyResolutionEnabled) { this.dependencyResolutionEnabled = dependencyResolutionEnabled; }
            public List<String> getDependencyAllowedRepositories() { return dependencyAllowedRepositories; }
            public void setDependencyAllowedRepositories(List<String> dependencyAllowedRepositories) { this.dependencyAllowedRepositories = dependencyAllowedRepositories; }
            public int getDependencyMaxArtifacts() { return dependencyMaxArtifacts; }
            public void setDependencyMaxArtifacts(int dependencyMaxArtifacts) { this.dependencyMaxArtifacts = dependencyMaxArtifacts; }
            public long getDependencyMaxBytes() { return dependencyMaxBytes; }
            public void setDependencyMaxBytes(long dependencyMaxBytes) { this.dependencyMaxBytes = dependencyMaxBytes; }
            public int getDependencyTimeoutSeconds() { return dependencyTimeoutSeconds; }
            public void setDependencyTimeoutSeconds(int dependencyTimeoutSeconds) { this.dependencyTimeoutSeconds = dependencyTimeoutSeconds; }

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

            public int getMaxSeedNodes() { return maxSeedNodes; }
            public void setMaxSeedNodes(int maxSeedNodes) { this.maxSeedNodes = maxSeedNodes; }
            public int getMaxEdgesPerNode() { return maxEdgesPerNode; }
            public void setMaxEdgesPerNode(int maxEdgesPerNode) { this.maxEdgesPerNode = maxEdgesPerNode; }
            public int getMaxCandidatesPerHop() { return maxCandidatesPerHop; }
            public void setMaxCandidatesPerHop(int maxCandidatesPerHop) { this.maxCandidatesPerHop = maxCandidatesPerHop; }
            public int getMaxTraversalRows() { return maxTraversalRows; }
            public void setMaxTraversalRows(int maxTraversalRows) { this.maxTraversalRows = maxTraversalRows; }

            public int getMaxLlmFiles() {
                return maxLlmFiles;
            }

            public void setMaxLlmFiles(int maxLlmFiles) {
                this.maxLlmFiles = maxLlmFiles;
            }

            public int getEnrichmentLeaseSeconds() { return enrichmentLeaseSeconds; }
            public void setEnrichmentLeaseSeconds(int enrichmentLeaseSeconds) { this.enrichmentLeaseSeconds = enrichmentLeaseSeconds; }
            public boolean isEvidenceRankingEnabled() { return evidenceRankingEnabled; }
            public void setEvidenceRankingEnabled(boolean evidenceRankingEnabled) { this.evidenceRankingEnabled = evidenceRankingEnabled; }
            public boolean isEvidenceRankingDebug() { return evidenceRankingDebug; }
            public void setEvidenceRankingDebug(boolean evidenceRankingDebug) { this.evidenceRankingDebug = evidenceRankingDebug; }
        }
    }

    public static class Document {
        @Min(1)
        private int indexThreads = 2;
        private Preview preview = new Preview();
        private Ocr ocr = new Ocr();
        private Graph graph = new Graph();
        private Enrichment enrichment = new Enrichment();

        public int getIndexThreads() {
            return indexThreads;
        }

        public void setIndexThreads(int indexThreads) {
            this.indexThreads = indexThreads;
        }

        public Preview getPreview() {
            return preview;
        }

        public void setPreview(Preview preview) {
            this.preview = preview;
        }

        public Ocr getOcr() {
            return ocr;
        }

        public void setOcr(Ocr ocr) {
            this.ocr = ocr;
        }

        public Graph getGraph() {
            return graph;
        }

        public void setGraph(Graph graph) {
            this.graph = graph;
        }

        public Enrichment getEnrichment() {
            return enrichment;
        }

        public void setEnrichment(Enrichment enrichment) {
            this.enrichment = enrichment;
        }

        public static class Preview {
            private boolean officeRenderEnabled = false;

            @NotBlank
            private String officeCommand = "soffice";

            @Min(1)
            private int officeTimeoutSeconds = 45;

            @Min(1)
            private long officeMaxFileBytes = 100_000_000L;

            public boolean isOfficeRenderEnabled() {
                return officeRenderEnabled;
            }

            public void setOfficeRenderEnabled(boolean officeRenderEnabled) {
                this.officeRenderEnabled = officeRenderEnabled;
            }

            public String getOfficeCommand() {
                return officeCommand;
            }

            public void setOfficeCommand(String officeCommand) {
                this.officeCommand = officeCommand;
            }

            public int getOfficeTimeoutSeconds() {
                return officeTimeoutSeconds;
            }

            public void setOfficeTimeoutSeconds(int officeTimeoutSeconds) {
                this.officeTimeoutSeconds = officeTimeoutSeconds;
            }

            public long getOfficeMaxFileBytes() {
                return officeMaxFileBytes;
            }

            public void setOfficeMaxFileBytes(long officeMaxFileBytes) {
                this.officeMaxFileBytes = officeMaxFileBytes;
            }
        }

        public static class Ocr {
            private boolean enabled = false;
            private String command = "ocrmypdf";
            private String languages = "kor+eng";

            @Min(0)
            private int minTextCharsPerPage = 80;

            @Min(1)
            private int maxPages = 80;

            @Min(1)
            private int timeoutSeconds = 180;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getCommand() { return command; }
            public void setCommand(String command) { this.command = command; }
            public String getLanguages() { return languages; }
            public void setLanguages(String languages) { this.languages = languages; }
            public int getMinTextCharsPerPage() { return minTextCharsPerPage; }
            public void setMinTextCharsPerPage(int minTextCharsPerPage) { this.minTextCharsPerPage = minTextCharsPerPage; }
            public int getMaxPages() { return maxPages; }
            public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
            public int getTimeoutSeconds() { return timeoutSeconds; }
            public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        }

        public static class Graph {
            private boolean enabled = true;

            @Min(1)
            private int maxHop = 1;

            @Min(1)
            private int maxExpandedResults = 12;

            private boolean debug = false;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public int getMaxHop() { return maxHop; }
            public void setMaxHop(int maxHop) { this.maxHop = maxHop; }
            public int getMaxExpandedResults() { return maxExpandedResults; }
            public void setMaxExpandedResults(int maxExpandedResults) { this.maxExpandedResults = maxExpandedResults; }
            public boolean isDebug() { return debug; }
            public void setDebug(boolean debug) { this.debug = debug; }
        }

        public static class Enrichment {
            private boolean deferWhenPrimaryActive = true;

            @Min(1)
            private int maxDeferSeconds = 900;

            public boolean isDeferWhenPrimaryActive() {
                return deferWhenPrimaryActive;
            }

            public void setDeferWhenPrimaryActive(boolean deferWhenPrimaryActive) {
                this.deferWhenPrimaryActive = deferWhenPrimaryActive;
            }

            public int getMaxDeferSeconds() {
                return maxDeferSeconds;
            }

            public void setMaxDeferSeconds(int maxDeferSeconds) {
                this.maxDeferSeconds = maxDeferSeconds;
            }
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

    public static class Retention {
        private boolean enabled = true;
        private boolean dryRun = false;

        @Min(1)
        private int operationLogDays = 14;

        @Min(1)
        private int auditLogDays = 180;

        @Min(1)
        private int exportDays = 14;

        @Min(1)
        private int orphanGraceDays = 7;

        @Min(1)
        private int dependencyCacheDays = 14;

        @Min(1)
        private int indexArtifactDays = 7;

        @Min(1)
        private int failedSourceDays = 7;

        @Min(1)
        private int orphanWorkspaceDays = 7;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public int getOperationLogDays() {
            return operationLogDays;
        }

        public void setOperationLogDays(int operationLogDays) {
            this.operationLogDays = operationLogDays;
        }

        public int getAuditLogDays() {
            return auditLogDays;
        }

        public void setAuditLogDays(int auditLogDays) {
            this.auditLogDays = auditLogDays;
        }

        public int getExportDays() {
            return exportDays;
        }

        public void setExportDays(int exportDays) {
            this.exportDays = exportDays;
        }

        public int getOrphanGraceDays() {
            return orphanGraceDays;
        }

        public void setOrphanGraceDays(int orphanGraceDays) {
            this.orphanGraceDays = orphanGraceDays;
        }

        public int getDependencyCacheDays() {
            return dependencyCacheDays;
        }

        public void setDependencyCacheDays(int dependencyCacheDays) {
            this.dependencyCacheDays = dependencyCacheDays;
        }

        public int getIndexArtifactDays() {
            return indexArtifactDays;
        }

        public void setIndexArtifactDays(int indexArtifactDays) {
            this.indexArtifactDays = indexArtifactDays;
        }

        public int getFailedSourceDays() {
            return failedSourceDays;
        }

        public void setFailedSourceDays(int failedSourceDays) {
            this.failedSourceDays = failedSourceDays;
        }

        public int getOrphanWorkspaceDays() {
            return orphanWorkspaceDays;
        }

        public void setOrphanWorkspaceDays(int orphanWorkspaceDays) {
            this.orphanWorkspaceDays = orphanWorkspaceDays;
        }
    }
}
