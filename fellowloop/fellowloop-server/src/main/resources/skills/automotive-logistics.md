---
name: automotive-logistics
description: Domain notes for automotive inbound and line-side logistics
---
# Automotive logistics

- Inbound: suppliers send advance shipping notices (ASN); goods receipt checks them against the delivery,
  records batch / lot and starts quality inspection when required.
- JIT (just in time): parts arrive close to when they are needed. JIS (just in sequence): parts arrive in the
  exact order of the vehicles on the assembly line, keyed by the production sequence (build order).
- Line-side supply: kanban or call-offs replenish stations; never deliver to a station without a demand.
- Traceability: safety-relevant parts keep batch / serial numbers from receipt to the vehicle.
- Quality hold (blocked stock) is never picked; releasing it needs an explicit, recorded decision.
- When in doubt about a business rule, state the assumption in your summary rather than inventing one.
