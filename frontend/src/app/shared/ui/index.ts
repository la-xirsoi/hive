/**
 * Hive design system - presentational primitives.
 *
 * Every component here is standalone, OnPush and free of injected services, so
 * feature modules can import exactly what they need:
 *
 *   import { HiveButton, HiveStatusBadge } from '../../shared/ui';
 */
export { HiveAvatar, initialsFor } from './avatar/avatar';
export type { HiveAvatarSize, HiveAvatarTone } from './avatar/avatar';

export { HiveButton } from './button/button';
export type { HiveButtonSize, HiveButtonType, HiveButtonVariant } from './button/button';

export { HiveCard } from './card/card';
export type { HiveCardElevation, HiveCardPadding } from './card/card';

export { HiveEmptyState } from './empty-state/empty-state';

export { HiveFormField } from './form-field/form-field';

export { HiveModal } from './modal/modal';
export type { HiveModalSize } from './modal/modal';

export { HivePageHeader } from './page-header/page-header';

export { HiveSpinner } from './spinner/spinner';
export type { HiveSpinnerSize, HiveSpinnerTone } from './spinner/spinner';

export { HiveStatusBadge, TASK_STATUSES } from './status-badge/status-badge';
export type { TaskStatus } from './status-badge/status-badge';

export { HiveToast } from './toast/toast';
export type { HiveToastVariant } from './toast/toast';

export { HiveUserChip } from './user-chip/user-chip';
export type { HiveChipUser } from './user-chip/user-chip';
