package ninibayr.karting.repository;
import ninibayr.karting.entity.HeatEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     * Какие из переданных заездов уже сохранены локально. Один запрос вместо
     * N обращений к БД при проверке списка на новые заезды.
     */
    @Query("select h.siteId from HeatEntity h where h.siteId in :siteIds")
    List<String> findExistingSiteIds(@Param("siteIds") Collection<String> siteIds);
}
