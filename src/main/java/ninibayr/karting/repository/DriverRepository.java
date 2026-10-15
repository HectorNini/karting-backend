package ninibayr.karting.repository;
import ninibayr.karting.entity.DriverEntity;
import ninibayr.karting.entity.TrackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DriverRepository extends JpaRepository<DriverEntity, Long> {
    Optional<DriverEntity> findBySiteIdAndTrack(String siteId, TrackEntity track);
}