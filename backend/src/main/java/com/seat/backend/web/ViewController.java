package com.seat.backend.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ViewController {

    @GetMapping("/")
    public String home() { return "index"; }

    @GetMapping("/login")
    public String login() { return "login"; }

    @GetMapping("/signup")
    public String signup() { return "signup"; }

    @GetMapping("/events/{eventId}/seats")
    public String seatPage(@PathVariable("eventId") Long eventId, Model model) {
        // Thymeleaf 3.1 부터 템플릿에서 #httpServletRequest 접근이 막혔으므로
        // 컨트롤러가 Model 로 eventId 를 주입한다.
        model.addAttribute("eventId", eventId);
        return "seats";   // /api/events/{id}/seats 는 데이터, /events/{id}/seats 는 화면
    }
}