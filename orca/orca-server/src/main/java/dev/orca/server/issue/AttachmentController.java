package dev.orca.server.issue;

import dev.orca.server.auth.AuthFilter;
import dev.orca.server.auth.AuthService;
import dev.orca.server.auth.CurrentUser;
import dev.orca.server.model.ApiToken;
import dev.orca.server.model.Attachment;
import dev.orca.server.model.Issue;
import dev.orca.server.repo.AttachmentRepo;
import dev.orca.server.repo.TaskRepo;
import dev.orca.server.support.ApiException;
import dev.orca.server.support.Ids;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Files on issues, mainly screenshots. People upload with their session; a running task may download
 * its own issue's files with its task token (that is how images reach the agent).
 */
@RestController
public class AttachmentController {

  static final long MAX_BYTES = 20L * 1024 * 1024;

  private final AttachmentRepo attachments;
  private final IssueService issues;
  private final AuthService auth;
  private final TaskRepo tasks;
  private final Path root;

  public AttachmentController(AttachmentRepo attachments, IssueService issues, AuthService auth, TaskRepo tasks,
      @Value("${orca.storage-dir:${user.home}/.orca/server/files}") String storage) {
    this.attachments = attachments;
    this.issues = issues;
    this.auth = auth;
    this.tasks = tasks;
    this.root = Path.of(storage).toAbsolutePath().normalize();
  }

  public record AttachmentView(String id, String issueId, String name, String contentType, long size, String url,
      long createdAt) {
    public static AttachmentView of(Attachment a) {
      return new AttachmentView(a.id, a.issueId, a.name, a.contentType, a.sizeBytes, "/api/attachments/" + a.id + "/file",
          a.createdAt);
    }
  }

  @PostMapping(path = "/api/issues/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public AttachmentView upload(@PathVariable String id, @RequestParam("file") MultipartFile file) throws IOException {
    Issue issue = issues.get(id);
    if (file.isEmpty() || file.getSize() > MAX_BYTES) {
      throw ApiException.bad("files must be between 1 byte and 20 MB");
    }
    Attachment a = new Attachment();
    a.id = Ids.next("att");
    a.issueId = issue.id;
    String name = file.getOriginalFilename() == null ? "file" : Path.of(file.getOriginalFilename()).getFileName().toString();
    a.name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
    a.contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType().toLowerCase(Locale.ROOT);
    a.sizeBytes = file.getSize();
    a.createdBy = CurrentUser.id();
    a.createdAt = Ids.now();
    Path dir = root.resolve(issue.id);
    Files.createDirectories(dir);
    Path target = dir.resolve(a.id);
    try (InputStream in = file.getInputStream()) {
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    a.storagePath = root.relativize(target).toString();
    return AttachmentView.of(attachments.save(a));
  }

  @GetMapping("/api/issues/{id}/attachments")
  public List<AttachmentView> list(@PathVariable String id) {
    return attachments.findByIssueIdOrderByCreatedAtAsc(issues.get(id).id).stream().map(AttachmentView::of).toList();
  }

  /** Session users, or the task token of a task working on the same issue. */
  @GetMapping("/api/attachments/{id}/file")
  public ResponseEntity<Resource> file(@PathVariable String id, HttpServletRequest req) {
    Attachment a = attachments.findById(id).orElseThrow(() -> ApiException.notFound("attachment"));
    String token = AuthFilter.token(req);
    boolean member = auth.verify(token, AuthService.SESSION).isPresent();
    boolean task = !member && auth.verify(token, AuthService.TASK).map(ApiToken::getLabel).flatMap(tasks::findById)
        .filter(t -> a.issueId.equals(t.issueId)).isPresent();
    if (!member && !task) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "not allowed");
    }
    Path p = root.resolve(a.storagePath).normalize();
    if (!p.startsWith(root) || !Files.exists(p)) {
      throw ApiException.notFound("file");
    }
    boolean image = a.contentType.startsWith("image/") && !a.contentType.contains("svg");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, image ? a.contentType : MediaType.APPLICATION_OCTET_STREAM_VALUE)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.builder(image ? "inline" : "attachment")
            .filename(a.name).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(new FileSystemResource(p));
  }

  public Optional<Attachment> find(String id) {
    return attachments.findById(id);
  }

  static Map<String, Object> none() {
    return Map.of();
  }
}
