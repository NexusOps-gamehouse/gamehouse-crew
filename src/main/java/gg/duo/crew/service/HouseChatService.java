package gg.duo.crew.service;

import gg.duo.crew.domain.chat.HouseChatMessage;
import gg.duo.crew.domain.chat.HouseChatMessageRepository;
import gg.duo.crew.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import gg.duo.crew.client.UserClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HouseChatService {

    private final HouseChatMessageRepository chatMessageRepository;
    private final HouseService houseService;
    private final UserClient userClient;

    /**
     * 인증된 userId를 기준으로 실제 사용자 닉네임을 조회한다.
     *
     * senderName을 클라이언트가 보내게 하지 않고 서버에서 직접 결정하므로,
     * 다른 사용자의 닉네임으로 메시지를 보내는 위조를 막는다.
     *
     * 예전에는 user 서비스의 테이블을 직접 SELECT 했다. crew 는 duo_crew 계정으로
     * 접속하고 그 계정에는 남의 스키마 권한이 없어서 그 쿼리는 항상 실패했다.
     */
    private String resolveSenderName(Long senderId, String storedSenderName) {
        if (storedSenderName != null && !storedSenderName.isBlank()) {
            return storedSenderName;
        }

        if (senderId == null) {
            return "House 멤버";
        }

        return displayName(senderId, userClient.findNickname(senderId));
    }

    /** 목록처럼 여러 건을 한 번에 그릴 때. 닉네임을 미리 받아둔 맵에서 꺼낸다. */
    private String resolveSenderName(
            Long senderId,
            String storedSenderName,
            Map<Long, String> nicknames
    ) {
        if (storedSenderName != null && !storedSenderName.isBlank()) {
            return storedSenderName;
        }

        if (senderId == null) {
            return "House 멤버";
        }

        return displayName(senderId, nicknames.get(senderId));
    }

    private String displayName(Long senderId, String nickname) {
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        return "사용자 #" + senderId;
    }

    /**
     * STOMP로 들어온 메시지를 저장한다.
     *
     * senderId와 senderName은 클라이언트 값을 신뢰하지 않고
     * WebSocket 인증 Principal의 userId를 기준으로 서버에서 결정한다.
     */
    @Transactional
    public ChatMessageDto save(ChatMessageDto dto, Long senderId) {
        houseService.requireApprovedMember(dto.getHouseId(), senderId);

        String senderName = resolveSenderName(senderId, null);

        dto.setSenderId(senderId);
        dto.setSenderName(senderName);
        dto.setTimestamp(LocalDateTime.now());

        chatMessageRepository.save(HouseChatMessage.from(dto));

        return dto;
    }

    /** 최근 50개 메시지를 오래된 순으로 반환한다. */
    @Transactional(readOnly = true)
    public List<ChatMessageDto> recent(Long houseId, Long userId) {
        houseService.requireApprovedMember(houseId, userId);

        List<HouseChatMessage> found =
                new ArrayList<>(
                        chatMessageRepository.findTop50ByHouseIdOrderByIdDesc(houseId)
                );

        java.util.Collections.reverse(found);

        // senderName 이 비어 있는 메시지의 작성자만 모아 한 번에 조회한다.
        // 건별로 물으면 50개짜리 목록 하나에 HTTP 왕복이 50번이다.
        Map<Long, String> nicknames = userClient.findNicknames(
                found.stream()
                        .filter(m -> m.getSenderName() == null || m.getSenderName().isBlank())
                        .map(HouseChatMessage::getSenderId)
                        .toList()
        );

        return found.stream()
                .map(message -> ChatMessageDto.builder()
                        .houseId(message.getHouseId())
                        .senderId(message.getSenderId())
                        .senderName(
                                resolveSenderName(
                                        message.getSenderId(),
                                        message.getSenderName(),
                                        nicknames
                                )
                        )
                        .message(message.getMessage())
                        .timestamp(message.getTimestamp())
                        .build())
                .toList();
    }
}
