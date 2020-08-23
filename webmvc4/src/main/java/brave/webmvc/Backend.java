package brave.webmvc;

import java.util.Date;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Slf4j
@Configuration
@EnableWebMvc
@RestController
public class Backend {

    @RequestMapping("/api")
    public String printDate(@RequestHeader(name = "user_name", required = false) String username) {
        if (username != null) {
            return new Date().toString() + " " + username;
        }
        String s = new Date().toString();
        log.info("username={};s={};", username, s);
        return s;
    }
}
