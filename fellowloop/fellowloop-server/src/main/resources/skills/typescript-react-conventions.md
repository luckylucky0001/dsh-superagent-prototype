---
name: typescript-react-conventions
description: Conventions for TypeScript and React front ends
---
# TypeScript / React

- `strict` TypeScript; no `any` unless unavoidable and commented.
- Function components and hooks; derive state instead of duplicating it.
- Server data through the project's data layer (e.g. TanStack Query); no fetch calls inside components.
- All user-visible text through the i18n layer; keep every language in sync.
- Accessible markup: labels for inputs, buttons for actions, keyboard reachable.
- Run `npm run typecheck`, the linter and the tests before you finish.
