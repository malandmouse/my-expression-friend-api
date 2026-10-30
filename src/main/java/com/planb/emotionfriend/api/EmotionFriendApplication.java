package com.planb.emotionfriend.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class EmotionFriendApplication {

	public static void main(String[] args) {
		SpringApplication.run(EmotionFriendApplication.class, args);
	}

}
