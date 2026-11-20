package dev.orca.server.agent;

import dev.orca.server.model.Skill;
import dev.orca.server.realtime.Realtime;
import dev.orca.server.repo.SkillRepo;
import dev.orca.server.support.ApiException;
import dev.orca.server.support.Ids;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/skills")
public class SkillController {
  private final SkillRepo skills;
  private final Realtime realtime;

  public SkillController(SkillRepo skills, Realtime realtime) {
    this.skills = skills;
    this.realtime = realtime;
  }

  public record SkillInput(String name, String description, String content) {}

  public record SkillView(String id, String name, String description, String content, long createdAt, long updatedAt) {
    static SkillView of(Skill s) {
      return new SkillView(s.id, s.name, s.description, s.content, s.createdAt, s.updatedAt);
    }
  }

  @GetMapping
  public List<SkillView> list() {
    return skills.findAllByOrderByNameAsc().stream().map(SkillView::of).toList();
  }

  @PostMapping
  @Transactional
  public SkillView create(@RequestBody SkillInput in) {
    Skill s = new Skill();
    s.id = Ids.next("skl");
    s.createdAt = Ids.now();
    apply(s, in);
    return SkillView.of(save(s));
  }

  @PutMapping("/{id}")
  @Transactional
  public SkillView update(@PathVariable String id, @RequestBody SkillInput in) {
    Skill s = skills.findById(id).orElseThrow(() -> ApiException.notFound("skill"));
    apply(s, in);
    return SkillView.of(save(s));
  }

  @DeleteMapping("/{id}")
  @Transactional
  public Map<String, Object> delete(@PathVariable String id) {
    skills.deleteById(id);
    realtime.publish("skill.updated", Realtime.ids("id", id, "deleted", true));
    return Map.of("ok", true);
  }

  private void apply(Skill s, SkillInput in) {
    if (in.name() == null || in.name().isBlank() || in.content() == null || in.content().isBlank()) {
      throw ApiException.bad("a skill needs a name and content");
    }
    String name = in.name().trim();
    skills.findByNameIgnoreCase(name).filter(o -> !o.id.equals(s.id)).ifPresent(o -> {
      throw ApiException.bad("a skill named " + name + " exists");
    });
    if (in.content().length() > 50_000) {
      throw ApiException.bad("keep a skill under 50,000 characters; agents read it on every task");
    }
    s.name = name;
    s.description = in.description();
    s.content = in.content();
    s.updatedAt = Ids.now();
  }

  private Skill save(Skill s) {
    Skill saved = skills.save(s);
    realtime.publish("skill.updated", Realtime.ids("id", s.id));
    return saved;
  }
}
