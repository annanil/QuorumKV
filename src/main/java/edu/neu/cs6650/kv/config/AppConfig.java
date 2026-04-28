package edu.neu.cs6650.kv.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

  @Bean
  public RestTemplate restTemplate() {
    PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
    connManager.setMaxTotal(200);           // total connections across all routes
    connManager.setDefaultMaxPerRoute(50);  // per-host (follower/peer) limit

    RequestConfig requestConfig = RequestConfig.custom()
        .setConnectTimeout(Timeout.ofSeconds(2))         // TCP handshake
        .setResponseTimeout(Timeout.ofSeconds(5))        // time to first byte of response
        .setConnectionRequestTimeout(Timeout.ofSeconds(2)) // wait for a connection from the pool
        .build();

    CloseableHttpClient httpClient = HttpClients.custom()
        .setConnectionManager(connManager)
        .setDefaultRequestConfig(requestConfig)
        .build();

    HttpComponentsClientHttpRequestFactory factory =
        new HttpComponentsClientHttpRequestFactory(httpClient);

    return new RestTemplate(factory);
  }
}
