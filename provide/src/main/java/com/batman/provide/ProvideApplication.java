package com.batman.provide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController("/hello")
public class ProvideApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProvideApplication.class, args);
    }

    @GetMapping("/rateLimit")
    public String hello(){
        return "hello ";
    }

    @GetMapping("/rateLimit/hello")
    public String limit(){
        return "limit ";
    }
}
