package gg.duo.crew.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashMap;

/**
 * user 서비스에서 닉네임을 가져온다.
 *
 * [왜 HTTP 인가 — 원래는 SQL 이었다]
 * 이전 구현은 crew 안에서 `SELECT nickname FROM user_svc.users` 를 직접 쏘았다.
 * crew 는 duo_crew 계정으로 접속하고 그 계정에는 user_svc 권한이 없어서
 * "permission denied for schema user_svc" 로 House API 가 전부 500 이 났다.
 *
 * 권한(GRANT)을 주는 쪽이 아니라 호출로 바꾼 이유는, 스키마·계정이 서비스 경계를
 * 강제하는 유일한 수단이기 때문이다. 레포가 갈라지면 그 GRANT 를 적어둘 자리도
 * 없어진다 — 각 레포는 자기 계정만 만든다.
 *
 * [묶음 조회인 이유]
 * user 서비스의 /internal/users 는 처음부터 묶음 API 다. House 목록 20개를
 * 단건으로 돌면 HTTP 왕복이 20번이다. 호출하는 쪽에서 id 를 모아 한 번에 묻는다.
 *
 * [user 가 죽어도 House 는 보여야 한다]
 * 실패하면 예외를 올리지 않고 빈 결과를 준다. 닉네임이 null 로 내려갈 뿐
 * 목록·상세 자체는 뜬다. 닉네임 때문에 화면 전체가 죽는 게 더 나쁘다.
 */
@Component
public class UserClient {

    private static final Logger log = LoggerFactory.getLogger(UserClient.class);

    /** user 서비스가 응답하지 않을 때 요청 스레드를 붙잡아 두지 않는다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final WebClient userServiceWebClient;

    public UserClient(@Qualifier("userServiceWebClient") WebClient userServiceWebClient) {
        this.userServiceWebClient = userServiceWebClient;
    }

    /** id 묶음 → {id: nickname}. 못 찾은 id 는 키 자체가 없다. */
    public Map<Long, String> findNicknames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }

        // null 제거 + 중복 제거. 빈 ids 로 호출하면 user 쪽이 400 을 준다.
        Set<Long> ids = new LinkedHashSet<>();
        userIds.stream().filter(Objects::nonNull).forEach(ids::add);

        if (ids.isEmpty()) {
            return Map.of();
        }

        try {
            List<JsonNode> body = userServiceWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/users")
                            .queryParam("ids", ids)
                            .build())
                    .retrieve()
                    .bodyToFlux(JsonNode.class)
                    .collectList()
                    .block(TIMEOUT);

            if (body == null) {
                return Map.of();
            }

            Map<Long, String> result = new HashMap<>();

            for (JsonNode node : body) {
                JsonNode idNode = node.path("id");
                if (idNode.isMissingNode() || idNode.isNull()) {
                    continue;
                }

                String nickname = node.path("nickname").asText(null);
                if (nickname == null || nickname.isBlank()) {
                    continue;
                }

                result.put(idNode.asLong(), nickname);
            }

            return result;
        } catch (Exception e) {
            // 여기서 던지면 House 목록 전체가 500 이 된다. 닉네임만 포기한다.
            log.warn("user 서비스 닉네임 조회 실패 (ids={}): {}", ids, e.toString());
            return Map.of();
        }
    }

    /** 단건. 승인·거절처럼 사용자 한 명만 필요한 곳에서 쓴다. */
    public String findNickname(Long userId) {
        if (userId == null) {
            return null;
        }
        return findNicknames(List.of(userId)).get(userId);
    }
}
