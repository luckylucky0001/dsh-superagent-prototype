package dev.orca.server.workflow.components;

import static dev.orca.server.workflow.components.Fields.d;
import static dev.orca.server.workflow.components.Fields.f;
import static dev.orca.server.workflow.components.Fields.out;
import static dev.orca.server.workflow.components.Fields.req;
import static dev.orca.server.workflow.components.Fields.select;

import dev.orca.workflow.spi.FieldKind;
import dev.orca.workflow.spi.NodeComponent;
import dev.orca.workflow.spi.NodeContext;
import dev.orca.workflow.spi.NodeDescriptor;
import dev.orca.workflow.spi.NodeResult;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Trigger components carry no behaviour; {@code TriggerService} decides when they fire. */
@Configuration
public class Triggers {

  private static NodeComponent trigger(NodeDescriptor d) {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return d;
      }

      public NodeResult execute(NodeContext ctx) {
        throw new UnsupportedOperationException("triggers are not executed");
      }
    };
  }

  @Bean
  NodeComponent manualTrigger() {
    return trigger(d("trigger.manual", "trigger", "Manual", "Start the workflow by hand, optionally with inputs.",
        true, List.of(f("inputs", "Input names (comma separated)", FieldKind.STRING)
            .withHelp("Shown as a form when someone runs the workflow; read them as trigger.input.<name>.")),
        List.of(), List.of(out("input", "object", "the values entered"), out("actor", "object", "who started it"))));
  }

  @Bean
  NodeComponent issueTrigger() {
    return trigger(d("trigger.issue", "trigger", "Issue event",
        "Fires when an issue is created, assigned, commented on or changes status.", true,
        List.of(select("event", "Event", true, "assigned", "assigned", "commented", "created", "status_changed"),
            select("target", "Requires an agent", false, "agent", "agent", "any")
                .withHelp("'agent': only when an agent is assigned or @mentioned."),
            f("agent", "Only for this agent", FieldKind.AGENT),
            f("label", "Only with this label", FieldKind.STRING),
            f("toStatus", "Only when the new status is", FieldKind.STRING),
            f("condition", "Extra condition", FieldKind.EXPRESSION)
                .withHelp("e.g. trigger.issue.priority == 'urgent'"),
            f("includeAutomation", "Also fire for changes made by agents or workflows", FieldKind.BOOLEAN)
                .withDefault(false)),
        List.of(), List.of(out("issue", "object", "the issue (id, key, title, description, status, labels…)"),
            out("comment", "object", "the comment, for 'commented'"),
            out("agentId", "string", "the assigned or mentioned agent"),
            out("event", "string", "the event name"))));
  }

  @Bean
  NodeComponent scheduleTrigger() {
    return trigger(d("trigger.schedule", "trigger", "Schedule", "Runs on a cron schedule.", true,
        List.of(req("cron", "Cron", FieldKind.CRON).withHelp("5 fields, e.g. '0 9 * * 1-5' for 09:00 on weekdays"),
            f("timezone", "Time zone", FieldKind.STRING).withDefault("Asia/Singapore"),
            f("payload", "Payload", FieldKind.JSON)),
        List.of(), List.of(out("firedAt", "number", "epoch millis"), out("payload", "object", "the configured payload"))));
  }

  @Bean
  NodeComponent webhookTrigger() {
    return trigger(d("trigger.webhook", "trigger", "Webhook",
        "Starts when something POSTs JSON to /api/hooks/{workflowId}/{nodeId} (secret in X-Orca-Secret or ?secret=). "
            + "A redelivery with the same Idempotency-Key, X-GitHub-Delivery or X-Gitlab-Event-UUID starts no second run.", true,
        List.of(req("secret", "Secret", FieldKind.STRING)),
        List.of(), List.of(out("body", "object", "the JSON body"), out("query", "object", "query parameters"),
            out("deliveryId", "string", "the delivery id, when the sender gave one"))));
  }
}
