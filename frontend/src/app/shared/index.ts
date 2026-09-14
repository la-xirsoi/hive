/**
 * Public surface of the Hive shared layer.
 *
 * `ui`     - presentational primitives (button, badge, card, modal, ...)
 * `layout` - the application shell. It is exported, NOT registered: whoever
 *            owns app.ts / app.routes.ts decides where to place it.
 */
export * from './ui';
export * from './layout';
