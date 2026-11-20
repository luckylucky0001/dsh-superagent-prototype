package dev.orca.workflow.spi;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/** Type id to component. Components come from the host application and from {@link ServiceLoader}. */
public final class ComponentRegistry {

  private final Map<String, NodeComponent> components = new ConcurrentHashMap<>();

  public ComponentRegistry register(NodeComponent component) {
    String type = component.descriptor().type();
    if (components.putIfAbsent(type, component) != null) {
      throw new IllegalStateException("duplicate component type: " + type);
    }
    return this;
  }

  public ComponentRegistry registerAll(Collection<? extends NodeComponent> list) {
    list.forEach(this::register);
    return this;
  }

  /** Adds plug-in components found on the class path via {@code META-INF/services}. */
  public ComponentRegistry loadPlugins(ClassLoader loader) {
    for (NodeComponent c : ServiceLoader.load(NodeComponent.class, loader)) {
      if (!components.containsKey(c.descriptor().type())) {
        register(c);
      }
    }
    return this;
  }

  public Optional<NodeComponent> find(String type) {
    return Optional.ofNullable(components.get(type));
  }

  public NodeComponent require(String type) {
    return find(type).orElseThrow(() -> new IllegalArgumentException("unknown component type: " + type));
  }

  public List<NodeDescriptor> descriptors() {
    return components.values().stream()
        .map(NodeComponent::descriptor)
        .sorted((a, b) -> (a.category() + a.type()).compareTo(b.category() + b.type()))
        .toList();
  }
}
