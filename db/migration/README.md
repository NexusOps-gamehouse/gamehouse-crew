# crew DB 마이그레이션 / 시드

> **이 폴더의 파일은 자동으로 실행되지 않는다.**
>
> crew 는 Flyway 를 쓰지 않는다. `application.yml` 의 `ddl-auto: update` 가
> **테이블 구조만** 만들고, **데이터는 넣지 않는다.**
> 여기 있는 SQL 은 새 환경을 세울 때 **사람이 직접 돌려야 한다.**
>
> 실제로 Main 을 새 RDS 로 띄운 뒤 꾸미기 상점이 "총 0개의 아이템" 으로 나온
> 사고가 있었다. 원인은 이 시드가 실행되지 않은 것이었고, 파일이 분리 전
> 모노레포(`backend/db/migration`)에만 남아 있어 찾는 데 오래 걸렸다.
> 그래서 crew 소관 파일(V5~V11)을 여기로 옮겨 왔다.

## 번호가 V5 부터 시작하는 이유

V1~V4 는 crew 것이 아니다. 각 서비스 스키마를 쓰는 다른 레포 소관이다.

| 번호 | 스키마 | 소관 |
| --- | --- | --- |
| V1, V2 | `user_svc` `post_svc` `chat_svc` | 서비스 분리 / FK 제거 |
| V3 | `user_svc` | 설문 개편 |
| V4 | `post_svc` | 모집글 게임 조건 |

번호는 **실행 순서 표시일 뿐**이다. Flyway 가 없으니 어딘가에 기록되지도,
건너뛴 번호가 문제를 일으키지도 않는다. 다만 순서는 지켜야 한다
(예: V11 은 V10 이 넣은 행의 가격을 고친다).

원본은 `backend/db/migration/` 에 그대로 두었다. 여기 있는 것과 내용이 같다.

## 파일 목록

| 파일 | 내용 | 새 환경에서 |
| --- | --- | --- |
| `V5__add_xp_hc_and_seed.sql` | `houses` 에 xp/hc 컬럼, 더미 상품 4종, House 1 에 HC 1만 | **실행하지 말 것** |
| `V6__add_shop_item_code.sql` | `shop_items.code` 컬럼 추가 + V5 상품에 code 부여 | **실행 불필요** |
| `V7__seed_profile_frame_catalog.sql` | 프로필 테두리 23종 | **실행** |
| `V8__add_house_activity_type_and_game.sql` | `houses` 에 activity_type / 대표 게임 | 실행 불필요 |
| `V9__create_house_ranking_snapshots.sql` | 랭킹 스냅샷 테이블 2개 | 실행 불필요 |
| `V10__seed_full_customization_catalog.sql` | 배너 8 · 휘장 14 · 채팅테마 6 | **실행** |
| `V11__fix_guardian_banner_price.sql` | `BANNER_GUARDIAN_NAME` 가격 300 → 200 | **실행** (V10 뒤) |

### 왜 V5·V6·V8·V9 는 실행하지 않나

- **V5** — 그냥 돌리면 **실패한다.** 뒤쪽 `shop_items` INSERT 가 `code` 를 주지 않는데
  `code` 는 NOT NULL 이다(`ShopItem.code` 의 `nullable = false`). V5 가 V6 보다 먼저
  쓰인 파일이라 생긴 순서 꼬임이다.
  앞부분의 `houses` xp/hc ALTER 는 `ddl-auto: update` 가 이미 만든다.
  설령 INSERT 가 통해도 `image_url` 이 `https://example.com/...` 인 더미 4종이라
  프론트의 `code` 매핑에 안 걸려 이미지 없는 회색 카드로 뜨고,
  House 1 에 HC 10000 을 주입하는 UPDATE 까지 들어 있어 운영에는 부적절하다.
  V6 이 참조하므로 **기록용으로만 보관한다.**
- **V6** — `code` 컬럼은 엔티티(`ShopItem.code`)에 선언돼 있어 `ddl-auto: update` 가
  이미 만든다. 나머지는 V5 상품 뒤처리라 새 환경에서는 대상이 없다.
- **V8·V9** — 컬럼·테이블 추가는 `ddl-auto: update` 가 처리한다.
  기존 행을 채우는 UPDATE 가 있지만 새 환경에는 채울 행이 없다.

즉 **새 환경에서 실제로 필요한 것은 V7 · V10 · V11 세 개뿐이다.**

## 실행 방법

세 파일 모두 `ON CONFLICT (code) DO UPDATE` 라 **몇 번 돌려도 안전하다.**
중복이 생기지 않고 값만 갱신된다.

### 로컬 / dev

```bash
cat V7__seed_profile_frame_catalog.sql \
    V10__seed_full_customization_catalog.sql \
    V11__fix_guardian_banner_price.sql \
| psql -h <host> -U duo_crew -d duo -v ON_ERROR_STOP=1
```

### Main (EKS + Private RDS)

Main RDS 는 Private Subnet 에 있어 밖에서 직접 붙지 못한다.
`gamehouse` 네임스페이스는 PodSecurity `restricted` 이고 NetworkPolicy 가
default-deny 이므로, **crew 라벨을 단 임시 파드**를 띄워 그 통신 규칙을 물려받는다.

`db/migration/psql-pod.yaml` 을 쓴다.

```bash
kubectl apply -f db/migration/psql-pod.yaml
kubectl wait --for=condition=Ready pod/psql-seed -n gamehouse --timeout=120s

# 연결 확인
kubectl exec -i psql-seed -n gamehouse -- psql -c "select current_user, current_database();"

# 시드 실행
cat db/migration/V7__seed_profile_frame_catalog.sql \
    db/migration/V10__seed_full_customization_catalog.sql \
    db/migration/V11__fix_guardian_banner_price.sql \
| kubectl exec -i psql-seed -n gamehouse -- psql -v ON_ERROR_STOP=1

# 확인 — BANNER 8 / BORDER 23 / CHAT_SKIN 6 / HOUSE_ICON 14
kubectl exec -i psql-seed -n gamehouse -- psql -c \
  "SELECT category, count(*) FROM crew_svc.shop_items GROUP BY category ORDER BY 1;"

# 반드시 정리 — DB 접속 정보를 들고 있는 파드다
kubectl delete pod psql-seed -n gamehouse
```

## 상품 이미지에 대해

`shop_items.image_url` 은 **전부 NULL 이고 쓰이지 않는다.**
프론트엔드가 `code` 를 키로 자기 번들의 이미지를 찾는다
(`frontend/src/components/ShopItemImage.jsx` → `mocks/customizationItems.js`).

그래서 **`code` 값을 절대 바꾸지 말 것.** 한 글자만 달라져도 그 상품만
이미지 없이 카테고리 이름만 뜬다. S3 나 CloudFront 와는 무관하다.
