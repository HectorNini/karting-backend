package ninibayr.karting.repository;
import ninibayr.karting.entity.TrackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TrackRepository extends JpaRepository<TrackEntity, Long> {
    Optional<TrackEntity> findBySlug(String slug);
}