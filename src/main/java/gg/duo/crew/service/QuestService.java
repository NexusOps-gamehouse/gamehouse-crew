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

        // 이번 주 행이 없으면 여기서 만든다.
        //
        // 행을 만드는 경로가 resetWeeklyQuests() 뿐인데, 그 메서드는 사실상
        // @PostConstruct(부팅 1회)로만 돈다 — CrewApplication 에 @EnableScheduling 이
        // 없어 @Scheduled 크론이 아예 등록되지 않기 때문이다. 그래서 재배포 없이
        // 주가 넘어가면 "이번 주 월요일" 로 조회했을 때 빈 목록이 된다
        // (2026-09-14 월요일에 실제로 발생).
        //
        // updateQuestProgress() 가 이미 같은 방식(orElseGet + save)을 쓰고 있어
        // 새로운 패턴은 아니다.
        for (QuestType type : QuestType.values()) {
            questRepository
                    .findByHouseIdAndQuestTypeAndWeekStartDateBetween(houseId, type, weekStart, weekEnd)
                    .orElseGet(() -> questRepository.save(new HouseQuest(houseId, type, weekStart)));
        }

        List<HouseQuest> quests = questRepository.findByHouseIdAndWeekStartDateBetween(
                houseId, weekStart, weekEnd
        );

        return quests.stream()
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
