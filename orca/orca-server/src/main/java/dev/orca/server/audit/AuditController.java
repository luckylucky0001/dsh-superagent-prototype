package dev.orca.server.audit;

import dev.orca.server.auth.CurrentUser;
import dev.orca.server.model.AuditEvent;
import dev.orca.server.repo.AuditRepo;
import dev.orca.server.support.ApiException;
import dev.orca.server.support.JsonCols;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit log, for owners and admins: newest first, paged by time, and as CSV for archiving. */
@RestController
public class AuditController {

  private final AuditRepo events;

  public AuditController(AuditRepo events) {
    this.events = events;
  }

  public record AuditView(String id, long at, String actorId, String actorName, String action, String targetType,
      String targetId, Map<String, Object> detail, String ip) {
    static AuditView of(AuditEvent e) {
      return new AuditView(e.id, e.at, e.actorId, e.actorName, e.action, e.targetType, e.targetId,
          e.detailJson == null ? Map.of() : JsonCols.map(e.detailJson), e.ip);
    }
  }

  @GetMapping("/api/audit")
  /** Newest first. {@code before}: the cursor {@code at:id} of the last event of the previous page. */
  public List<AuditView> list(@RequestParam(required = false) String before,
      @RequestParam(defaultValue = "100") int limit) {
    requireAdmin();
    int n = Math.max(1, Math.min(limit, 500));
    long at = Long.MAX_VALUE;
    String id = "~";
    if (before != null && !before.isBlank()) {
      int colon = before.indexOf(':');
      try {
        at = Long.parseLong(colon < 0 ? before : before.substring(0, colon));
      } catch (NumberFormatException e) {
        throw dev.orca.server.support.ApiException.bad("before must be at:id");
      }
      id = colon < 0 ? "~" : before.substring(colon + 1);
    }
    return events.page(at, id, PageRequest.of(0, n)).stream().map(AuditView::of).toList();
  }

  /** {@code from}/{@code to} in epoch milliseconds; defaults to the last 90 days. */
  @GetMapping("/api/audit.csv")
  public void csv(@RequestParam(required = false) Long from, @RequestParam(required = false) Long to,
      HttpServletResponse res) throws IOException {
    requireAdmin();
    long end = to == null ? System.currentTimeMillis() : to;
    long start = from == null ? end - 90L * 86_400_000L : from;
    res.setContentType("text/csv; charset=UTF-8");
    res.setHeader("Content-Disposition", "attachment; filename=\"orca-audit.csv\"");
    PrintWriter w = new PrintWriter(new java.io.OutputStreamWriter(res.getOutputStream(), StandardCharsets.UTF_8));
    w.write('﻿'); // so spreadsheet tools read UTF-8
    w.println("time_utc,actor,action,target_type,target_id,detail,ip");
    // streamed in keyset pages, so a long range never sits in memory at once
    long at = start - 1;
    String id = "";
    while (true) {
      List<AuditEvent> page = events.after(at, id, end, PageRequest.of(0, 1000));
      for (AuditEvent e : page) {
        w.println(String.join(",", cell(Instant.ofEpochMilli(e.at).toString()), cell(e.actorName), cell(e.action),
            cell(e.targetType), cell(e.targetId), cell(e.detailJson), cell(e.ip)));
      }
      w.flush();
      if (page.size() < 1000) {
        break;
      }
      at = page.get(page.size() - 1).at;
      id = page.get(page.size() - 1).id;
    }
  }

  /** CSV cell, quoted; a leading = + - @ is neutralised so spreadsheets do not run it as a formula. */
  public static String cell(String v) {
    if (v == null) {
      return "";
    }
    String s = v;
    if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
      s = "'" + s;
    }
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }

  private static void requireAdmin() {
    String role = CurrentUser.get().role;
    if (!"owner".equals(role) && !"admin".equals(role)) {
      throw new ApiException(HttpStatus.FORBIDDEN, "only owners and admins can read the audit log");
    }
  }
}
