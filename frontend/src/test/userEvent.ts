import base, { PointerEventsCheckLevel, type UserEvent } from '@testing-library/user-event'

/**
 * user-event preconfigured for this suite: no artificial delay between actions and no
 * pointer-events CSS check. The check walks `getComputedStyle` up the (large) antd DOM tree on
 * every interaction, which is very slow in jsdom and made tests time out under parallel runs.
 * Assertions on results are unchanged; tests that need the check can pass options to `setup`.
 */
const DEFAULTS = { delay: null, pointerEventsCheck: PointerEventsCheckLevel.Never } as const

function setup(options: Parameters<typeof base.setup>[0] = {}): UserEvent {
  return base.setup({ ...DEFAULTS, ...options })
}

const userEvent = {
  setup,
  click: (...args: Parameters<UserEvent['click']>) => setup().click(...args),
  type: (...args: Parameters<UserEvent['type']>) => setup().type(...args),
  clear: (...args: Parameters<UserEvent['clear']>) => setup().clear(...args),
}

export default userEvent
