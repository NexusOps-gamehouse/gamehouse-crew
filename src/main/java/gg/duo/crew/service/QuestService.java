package gg.duo.crew.service;

import gg.duo.crew.domain.HouseQuest;
import gg.duo.crew.domain.QuestType;
import gg.duo.crew.domain.house.House;
import gg.duo.crew.domain.house.HouseRepository;
import gg.duo.crew.dto.HouseQuestResponseDto;
import gg.duo.crew.repository.HouseQuestRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import gg.duo.crew.util.HouseWeekUtil;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuestService {

    private final HouseQuestRepository questRepository;
    private final HouseRepository houseRepository;
    private final HouseService houseService; // 멤버십 보안 검증용

    public LocalDateTime getStartOfCurrentWeek() {
        return HouseWeekUtil.startOfCurrentWeek();
    }

    public LocalDateTime getEndOfCurrentWeek() {
        return HouseWeekUtil.endOfCurrentWeek();
    }

    // 주간 퀘스트 조회 (멤버십 검증 포함)
    //
    // 클래스에 @Transactional(readOnly = true) 가 걸려 있어, 아래에서 행을 만들려면
    // 메서드에 쓰기 트랜잭션을 다시 선언해야 한다.
    @Transactional
    public List<HouseQuestResponseDto> getWeeklyQuests(Long houseId, Long userId) {
        houseService.requireApprovedMember(houseId, userId); // 🔒 멤버십 검증

        LocalDateTime weekStart = getStartOfCurrentWeek();
        LocalDateTime weekEnd = getEndOfCurrentWeek();

        List<HouseQuest> quests = questRepository.findByHouseIdAndWeekStartDateBetween(
                houseId, weekStart, weekEnd
        );

        // 이번 주 행이 하나도 없으면 여기서 만든다.
        //
        // 행을 만드는 경로가 resetWeeklyQuests() 뿐인데 그 메서드는 사실상
        // @PostConstruct(부팅 1회)로만 돈다 — CrewApplication 에 @EnableScheduling 이
        // 없어 @Scheduled 크론이 등록되지 않기 때문이다. 그래서 재배포 없이 주가
        // 넘어가면 빈 목록이 됐다 (2026-09-14 발생).
        if (quests.isEmpty()) {
            for (QuestType type : QuestType.values()) {
                questRepository.save(new HouseQuest(houseId, type, weekStart));
            }
            quests = questRepository.findByHouseIdAndWeekStartDateBetween(
                    houseId, weekStart, weekEnd
            );
        }

        // ⚠️ 타입별 Optional 조회(findByHouseIdAndQuestTypeAndWeekStartDateBetween)를
        //    여기서 쓰면 안 된다. resetWeeklyQuests() 의 @PostConstruct 가 중복 체크
        //    없이 INSERT 하고 replicas 가 2라, 배포할 때마다 같은 (하우스·타입·주차)
        //    행이 늘어난다. 실제로 5개까지 쌓여 NonUniqueResultException 으로 500 이
        //    났다 (2026-09-14). 리스트 조회는 개수와 무관하게 안전하다.
        //
        //    이미 쌓인 중복 행은 건드리지 않고, 응답에서 타입당 하나만 내보낸다.
        //    진행도가 큰 쪽을 남긴다. EnumMap 이라 순서는 QuestType 선언 순서다.
        Map<QuestType, HouseQuest> unique = new EnumMap<>(QuestType.class);
        for (HouseQuest quest : quests) {
            if (quest.getQuestType() == null) continue;
            unique.merge(quest.getQuestType(), quest,
                    (a, b) -> a.getCurrentCount() >= b.getCurrentCount() ? a : b);
        }

        return unique.values().stream()
                .map(HouseQuestResponseDto::from)
                .collect(Collectors.toList());
    }

    // 퀘스트 보상 수령 (멤버십 검증 및 중복 수령 방지)
    @Transactional
    public void claimQuestReward(Long houseId, Long questId, Long userId) {
        houseService.requireApprovedMember(houseId, userId); // 🔒 멤버십 검증

        HouseQuest quest = questRepository.findById(questId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 퀘스트입니다."));

        if (!quest.isCompleted() || quest.isRewardClaimed()) {
            throw new IllegalStateException("보상을 수령할 수 없는 상태입니다.");
        }

        House house = houseRepository.findById(houseId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 하우스입니다."));

        QuestType questType = quest.getQuestType();

        if (questType != null) {
            house.addReward(questType.getRewardXp(), questType.getRewardHc());
        }

        quest.claimReward();
    }

    @PostConstruct
    @Scheduled(cron = "0 0 0 * * MON")
    @Transactional
    public void resetWeeklyQuests() {
        LocalDateTime currentWeekStart = getStartOfCurrentWeek();
        houseRepository.findAll().forEach(house -> {
            for (QuestType type : QuestType.values()) {
                questRepository.save(new HouseQuest(house.getId(), type, currentWeekStart));
            }
        });
    }
}
