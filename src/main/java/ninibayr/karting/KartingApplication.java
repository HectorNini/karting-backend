package ninibayr.karting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KartingApplication {

	public static void main(String[] args) {
		SpringApplication.run(KartingApplication.class, args);
	}

}
