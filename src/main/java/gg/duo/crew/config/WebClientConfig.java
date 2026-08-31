package gg.duo.crew.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * user 서비스 호출용 WebClient.
 *
 * base-url 은 application.yml 의 services.user.base-url 을 그대로 쓴다 —
 * 선언은 이미 있었는데 쓰는 코드가 없었다(그래서 닉네임을 SQL 로 긁고 있었다).
 * match/WebClientConfig 와 같은 형태다.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient userServiceWebClient(@Value("${services.user.base-url}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }
}
