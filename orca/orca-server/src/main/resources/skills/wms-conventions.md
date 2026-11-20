---
name: wms-conventions
description: Warehouse rules every change must respect
---
# Warehouse conventions

- Bin codes are `ZONE-AISLE-RACK-LEVEL`, e.g. `A-01-02-3`.
  Zone A: fast movers next to the line; B: medium movers; C: high-bay store; R: receiving.
- Quantities are integers in the part's base unit (pieces). Stock never goes negative.
- Bins on quality hold (blocked) are never used for putaway or picking, and a hold must take effect atomically.
- Every stock movement (receipt, putaway, pick, transfer, adjustment) is recorded with who, when and why.
- Line-side supply is picked FIFO by receipt time unless the part is batch- or expiry-managed (then FEFO).
- A short pick changes nothing: allocate and deduct in one step, or not at all.
