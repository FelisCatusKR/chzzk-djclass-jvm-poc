package kr.felis.djclass.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
	@GetMapping("/")
	String index(Model model) {
		model.addAttribute("title", "치지직 DJ CLASS 채팅 위젯");
		return "index";
	}
}
