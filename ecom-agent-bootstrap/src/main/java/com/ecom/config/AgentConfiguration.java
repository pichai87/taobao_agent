package com.ecom.config;

import com.ecom.agent.AnalysisWorkflow;
import com.ecom.application.RunService;
import com.ecom.domain.Ports.*;
import com.ecom.infrastructure.*;
import com.ecom.knowledge.RulePlanner;
import com.zaxxer.hikari.*;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.retry.support.RetryTemplate;
import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;

@Configuration
public class AgentConfiguration {
    @Bean @Primary @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(@Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String user, @Value("${spring.datasource.password}") String password) {
        HikariConfig c=new HikariConfig(); c.setJdbcUrl(url); c.setUsername(user); c.setPassword(password);
        c.setMaximumPoolSize(8); c.setPoolName("application-writer"); return new HikariDataSource(c);
    }
    @Bean(destroyMethod="close")
    HikariDataSource readerDataSource(@Value("${agent.reader.url:${spring.datasource.url}}") String url,
            @Value("${agent.reader.username:${spring.datasource.username}}") String user,
            @Value("${agent.reader.password:${spring.datasource.password}}") String password) {
        HikariConfig c=new HikariConfig(); c.setJdbcUrl(url); c.setUsername(user); c.setPassword(password);
        c.setMaximumPoolSize(4); c.setReadOnly(true); c.setPoolName("analytics-reader"); return new HikariDataSource(c);
    }
    @Bean @Primary JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource ds) {return new JdbcTemplate(ds);}
    @Bean AnalyticsReader analyticsReader(@Qualifier("readerDataSource") DataSource ds,JdbcTemplate db) {
        return new JdbcAnalyticsReader(new JdbcTemplate(ds),db);
    }
    @Bean @ConditionalOnProperty(name="agent.model.mode",havingValue="offline",matchIfMissing=true)
    Planner offlinePlanner() {return new RulePlanner();}
    @Bean @ConditionalOnProperty(name="agent.model.mode",havingValue="llm")
    Planner modelPlanner(@Value("${MODEL_API_KEY}") String key,@Value("${MODEL_BASE_URL}") String base,
                         @Value("${MODEL_NAME}") String name) {
        if(key.isBlank() || name.isBlank() || !base.startsWith("https://"))
            throw new IllegalArgumentException("模型配置必须包含密钥、模型名和 HTTPS 地址");
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(25));
        var api=OpenAiApi.builder().apiKey(key).baseUrl(base)
            .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        var model=OpenAiChatModel.builder().openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().model(name).build())
            .retryTemplate(RetryTemplate.builder().maxAttempts(1).fixedBackoff(100).build()).build();
        return new ModelPlanner(model);
    }
    @Bean(destroyMethod="shutdown") ExecutorService agentExecutor() {
        return new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
            new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean AnalysisWorkflow workflow(Planner planner,Knowledge knowledge,AnalyticsReader reader,RunStore store) {
        return new AnalysisWorkflow(planner,knowledge,reader,store);
    }
    @Bean @DependsOn("flywayInitializer")
    RunUseCases runs(RunStore store,Planner planner,AnalysisWorkflow workflow,ExecutorService agentExecutor) {
        return new RunService(store,planner,workflow,agentExecutor);
    }
}

