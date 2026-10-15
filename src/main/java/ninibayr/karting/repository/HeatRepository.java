package ninibayr.karting.repository;
import ninibayr.karting.entity.HeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface HeatRepository extends JpaRepository<HeatEntity, Long> {
    Optional<HeatEntity> findBySiteId(String siteId);
}