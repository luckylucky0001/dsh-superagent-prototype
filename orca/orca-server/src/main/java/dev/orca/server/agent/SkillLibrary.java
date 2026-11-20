package dev.orca.server.agent;

import dev.orca.server.model.Skill;
import dev.orca.server.repo.SkillRepo;
import dev.orca.server.support.Ids;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Skills shipped with Orca ({@code classpath:skills/*.md}, each with a {@code name}/{@code description}
 * header). They are added on first start and can be imported again from Settings → Skills.
 */
@Component
public class SkillLibrary {

  public record Entry(String name, String description, String content) {}

  private final List<Entry> entries;

  public SkillLibrary() {
    List<Entry> list = new ArrayList<>();
    try {
      for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:skills/*.md")) {
        try (InputStream in = r.getInputStream()) {
          parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).ifPresent(list::add);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("could not read the skill library", e);
    }
    list.sort(Comparator.comparing(Entry::name));
    this.entries = List.copyOf(list);
  }

  public List<Entry> entries() {
    return entries;
  }

  public Optional<Entry> find(String name) {
    return entries.stream().filter(e -> e.name().equalsIgnoreCase(name)).findFirst();
  }

  /** Adds the library's skills that are not installed yet; returns how many were added. */
  public int installMissing(SkillRepo skills) {
    int added = 0;
    for (Entry e : entries) {
      if (skills.findByNameIgnoreCase(e.name()).isEmpty()) {
        skills.save(toSkill(e));
        added++;
      }
    }
    return added;
  }

  static Skill toSkill(Entry e) {
    Skill s = new Skill();
    s.id = Ids.next("skl");
    s.name = e.name();
    s.description = e.description();
    s.content = e.content();
    s.createdAt = s.updatedAt = Ids.now();
    return s;
  }

  /** {@code ---\nname: x\ndescription: y\n---\nbody}. */
  static Optional<Entry> parse(String text) {
    String t = text.replace("\r\n", "\n");
    if (!t.startsWith("---\n")) {
      return Optional.empty();
    }
    int end = t.indexOf("\n---\n", 4);
    if (end < 0) {
      return Optional.empty();
    }
    String name = null;
    String description = null;
    for (String line : t.substring(4, end).split("\n")) {
      int colon = line.indexOf(':');
      if (colon > 0) {
        String key = line.substring(0, colon).trim();
        String value = line.substring(colon + 1).trim();
        if (key.equals("name")) {
          name = value;
        } else if (key.equals("description")) {
          description = value;
        }
      }
    }
    String body = t.substring(end + 5).strip();
    return name == null || body.isEmpty() ? Optional.empty() : Optional.of(new Entry(name, description, body));
  }
}
