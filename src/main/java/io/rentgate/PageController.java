package io.rentgate;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.ui.Model;

@Controller
public class PageController {
    @GetMapping("/login")
    public String login() { return "login"; }

    @GetMapping("/r/{token}")
    public String rental(@PathVariable String token,Model model) {
        model.addAttribute("guestToken",token); return "guest";
    }
}
