package org.zalando.riptide.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import net.jodah.failsafe.CircuitBreaker;
import net.jodah.failsafe.RetryPolicy;
import org.apache.http.ConnectionClosedException;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContexts;
import org.springframework.boot.actuate.autoconfigure.MetricsDropwizardAutoConfiguration;
import org.springframework.boot.actuate.metrics.GaugeService;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.AsyncClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.web.client.AsyncRestTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriTemplateHandler;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.httpclient.LogbookHttpRequestInterceptor;
import org.zalando.logbook.httpclient.LogbookHttpResponseInterceptor;
import org.zalando.logbook.spring.LogbookAutoConfiguration;
import org.zalando.riptide.Http;
import org.zalando.riptide.OriginalStackTracePlugin;
import org.zalando.riptide.UrlResolution;
import org.zalando.riptide.backup.BackupRequestPlugin;
import org.zalando.riptide.failsafe.FailsafePlugin;
import org.zalando.riptide.faults.FaultClassifier;
import org.zalando.riptide.faults.TransientFaultException;
import org.zalando.riptide.faults.TransientFaultPlugin;
import org.zalando.riptide.httpclient.RestAsyncClientHttpRequestFactory;
import org.zalando.riptide.metrics.MetricsPlugin;
import org.zalando.riptide.spring.PluginTest.CustomPlugin;
import org.zalando.riptide.stream.Streams;
import org.zalando.riptide.timeout.TimeoutPlugin;
import org.zalando.stups.oauth2.httpcomponents.AccessTokensRequestInterceptor;
import org.zalando.stups.tokens.AccessTokens;
import org.zalando.stups.tokens.JsonFileBackedClientCredentialsProvider;
import org.zalando.stups.tokens.JsonFileBackedUserCredentialsProvider;
import org.zalando.stups.tokens.Tokens;
import org.zalando.tracer.Tracer;
import org.zalando.tracer.concurrent.TracingExecutors;
import org.zalando.tracer.httpclient.TracerHttpRequestInterceptor;
import org.zalando.tracer.spring.TracerAutoConfiguration;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.function.Predicate;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.http.conn.ssl.SSLConnectionSocketFactory.getDefaultHostnameVerifier;

@Configuration
public class ManualConfiguration {

    @Configuration
    static class SharedConfiguration {

        @Bean(destroyMethod = "stop")
        public AccessTokens tokens() {
            return Tokens.createAccessTokensWithUri(URI.create("https://auth.example.com"))
                    .usingClientCredentialsProvider(
                            new JsonFileBackedClientCredentialsProvider(new File("/credentials/client.json")))
                    .usingUserCredentialsProvider(
                            new JsonFileBackedUserCredentialsProvider(new File("/credentials/user.json")))
                    .schedulingPeriod(5)
                    .schedulingTimeUnit(SECONDS)
                    .connectionRequestTimeout(1000)
                    .socketTimeout(2000)
                    .manageToken("example")
                    .addScope("uid")
                    .addScope("example.read")
                    .done()
                    .start();
        }

    }

    @Configuration
    // just for documentation, should not be imported manually
    @Import({
            LogbookAutoConfiguration.class,
            TracerAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            MetricsDropwizardAutoConfiguration.class,
            SharedConfiguration.class,
    })
    static class ExampleClientConfiguration {

        @Bean
        public Http exampleHttp(final AsyncClientHttpRequestFactory requestFactory,
                final ClientHttpMessageConverters converters, final GaugeService gaugeService,
                final ScheduledExecutorService scheduler) {
            return Http.builder()
                    .baseUrl("https://www.example.com")
                    .urlResolution(UrlResolution.RFC)
                    .requestFactory(requestFactory)
                    .converters(converters.getConverters())
                    .plugin(new MetricsPlugin(gaugeService, new ZMONMetricsNameGenerator()))
                    .plugin(new TransientFaultPlugin(
                            FaultClassifier.create(ImmutableList.<Predicate<Throwable>>builder()
                                    .addAll(FaultClassifier.defaults())
                                    .add(ConnectionClosedException.class::isInstance)
                                    .add(NoHttpResponseException.class::isInstance)
                                    .build())))
                    .plugin(new FailsafePlugin(scheduler)
                            .withRetryPolicy(new RetryPolicy()
                                    .retryOn(TransientFaultException.class)
                                    .withBackoff(50, 2000, MILLISECONDS)
                                    .withMaxRetries(10)
                                    .withMaxDuration(2, SECONDS)
                                    .withJitter(0.2))
                            .withCircuitBreaker(new CircuitBreaker()
                                    .withFailureThreshold(5, 5)
                                    .withDelay(30, SECONDS)
                                    .withSuccessThreshold(3, 5)
                                    .withTimeout(3, SECONDS)))
                    .plugin(new BackupRequestPlugin(scheduler, 10, MILLISECONDS))
                    .plugin(new TimeoutPlugin(scheduler, 3, SECONDS))
                    .plugin(new OriginalStackTracePlugin())
                    .plugin(new CustomPlugin())
                    .build();
        }

        @Bean
        public RestTemplate exampleRestTemplate(final ClientHttpRequestFactory requestFactory,
                final ClientHttpMessageConverters converters) {
            final RestTemplate template = new RestTemplate();

            final DefaultUriTemplateHandler handler = new DefaultUriTemplateHandler();
            handler.setBaseUrl("https://www.example.com");
            template.setUriTemplateHandler(handler);
            template.setRequestFactory(requestFactory);
            template.setMessageConverters(converters.getConverters());

            return template;
        }

        @Bean
        public AsyncRestTemplate exampleAsyncRestTemplate(final AsyncClientHttpRequestFactory requestFactory,
                final ClientHttpMessageConverters converters) {
            final AsyncRestTemplate template = new AsyncRestTemplate();

            final DefaultUriTemplateHandler handler = new DefaultUriTemplateHandler();
            handler.setBaseUrl("https://www.example.com");
            template.setUriTemplateHandler(handler);
            template.setAsyncRequestFactory(requestFactory);
            template.setMessageConverters(converters.getConverters());

            return template;
        }

        @Bean
        public RestAsyncClientHttpRequestFactory exampleAsyncClientHttpRequestFactory(
                final AccessTokens tokens, final Tracer tracer, final Logbook logbook,
                final ExecutorService executor) throws Exception {
            return new RestAsyncClientHttpRequestFactory(
                    HttpClientBuilder.create()
                            .setDefaultRequestConfig(RequestConfig.custom()
                                    .setConnectTimeout(5000)
                                    .setSocketTimeout(5000)
                                    .build())
                            .setConnectionTimeToLive(30, SECONDS)
                            .setMaxConnPerRoute(2)
                            .setMaxConnTotal(20)
                            .addInterceptorFirst(new AccessTokensRequestInterceptor("example", tokens))
                            .addInterceptorFirst(new TracerHttpRequestInterceptor(tracer))
                            .addInterceptorLast(new LogbookHttpRequestInterceptor(logbook))
                            .addInterceptorLast(new GzippingHttpRequestInterceptor())
                            .addInterceptorLast(new LogbookHttpResponseInterceptor())
                            .setSSLSocketFactory(new SSLConnectionSocketFactory(
                                    SSLContexts.custom()
                                            .loadTrustMaterial(
                                                    getClass().getClassLoader().getResource("example.keystore"),
                                                    "password".toCharArray())
                                            .build(),
                                    getDefaultHostnameVerifier()))
                            .build(),
                    new ConcurrentTaskExecutor(executor));
        }

        @Bean(destroyMethod = "shutdown")
        public ExecutorService executor(final Tracer tracer) {
            return TracingExecutors.preserve(
                    new ThreadPoolExecutor(
                            1, 20, 1, MINUTES,
                            new ArrayBlockingQueue<>(0),
                            new CustomizableThreadFactory("http-example-"),
                            new AbortPolicy()),
                    tracer);
        }

        @Bean(destroyMethod = "shutdown")
        public ScheduledExecutorService scheduler(final Tracer tracer) {
            return TracingExecutors.preserve(
                    Executors.newScheduledThreadPool(
                            20, // TODO max-connections-total?
                            new CustomizableThreadFactory("http-example-scheduler-")),
                    tracer);
        }

        @Bean
        public ClientHttpMessageConverters exampleHttpMessageConverters(final ObjectMapper mapper) {
            final StringHttpMessageConverter textConverter = new StringHttpMessageConverter();
            textConverter.setWriteAcceptCharset(false);

            return new ClientHttpMessageConverters(Arrays.asList(
                    textConverter,
                    new MappingJackson2HttpMessageConverter(mapper),
                    Streams.streamConverter(mapper)
            ));
        }

    }

}
