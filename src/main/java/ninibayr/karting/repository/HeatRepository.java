package ninibayr.karting.repository;
import ninibayr.karting.entity.HeatEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface HeatRepository extends JpaRepository<HeatEntity, Long> {
    Optional<HeatEntity> findBySiteId(String siteId);

    /**
     * То же, но с блокировкой строки на время транзакции: сериализует
     * одновременные синхронизации одного заезда (шедулер + запрос из приложения).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from HeatEntity h where h.siteId = :siteId")
    Optional<HeatEntity> findBySiteIdForUpdate(@Param("siteId") String siteId);

    /**
     * Заезды, у которых есть круги, но ещё нет ни одного стинта: данные сохранены
     * старым парсером и их надо перечитать с сайта.
     */
    @Query("""
            select h from HeatEntity h
            where exists (select 1 from LapEntity l where l.heat = h)
              and not exists (select 1 from HeatStintEntity s where s.heat = h)
            order by h.id desc
            """)
    List<HeatEntity> findMissingStints(Pageable pageable);

    /**
     * Кандидаты для дневного фонового обновления: сегодняшние заезды и всё,
     * что менялось после cutoff. Вместо findAll() — чтобы не тянуть всю БД.
     */
    @Query("""
            select h from HeatEntity h
            where h.raceDate = :today or h.lastUpdated > :cutoff
            """)
    List<HeatEntity> findDayCandidates(@Param("today") LocalDate today,
                                       @Param("cutoff") LocalDateTime cutoff);

    /**
     * Кандидаты для ночного обновления: только заезды, которые ещё могли идти
     * (сегодня и стартовали не раньше fromTime). В обычную ночь — пусто.
     */
    @Query("""
            select h from HeatEntity h
            where h.raceDate = :today and h.raceTime >= :fromTime
            """)
    List<HeatEntity> findNightCandidates(@Param("today") LocalDate today,
                                         @Param("fromTime") LocalTime fromTime);
}
