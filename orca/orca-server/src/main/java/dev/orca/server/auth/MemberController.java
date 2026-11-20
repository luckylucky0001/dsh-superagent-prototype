package dev.orca.server.auth;

import dev.orca.server.repo.MemberRepo;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemberController {
  private final MemberRepo members;

  public MemberController(MemberRepo members) {
    this.members = members;
  }

  @GetMapping("/api/members")
  public List<AuthController.MemberView> list() {
    return members.findAll().stream().map(AuthController.MemberView::of).toList();
  }
}
