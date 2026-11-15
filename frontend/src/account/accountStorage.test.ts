import type { Account } from '../api/types'
import {
  ACCOUNT_STORAGE_KEY,
  canSelectAllAccounts,
  readStoredAccountId,
  resolveAccountId,
  writeStoredAccountId,
} from './accountStorage'

const account = (id: number): Account => ({
  id,
  name: `A${String(id)}`,
  brokerNote: null,
  createdAt: '2026-11-12T15:00:00Z',
})

describe('accountStorage (FEAT-033, Q55)', () => {
  it('remembers the selected account in localStorage', () => {
    expect(readStoredAccountId()).toBeNull()
    writeStoredAccountId(2)
    expect(window.localStorage.getItem(ACCOUNT_STORAGE_KEY)).toBe('2')
    expect(readStoredAccountId()).toBe(2)
  })

  it('ignores a corrupted value', () => {
    window.localStorage.setItem(ACCOUNT_STORAGE_KEY, 'abc')
    expect(readStoredAccountId()).toBeNull()
  })

  it('survives storage that throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    expect(readStoredAccountId()).toBeNull()
    expect(() => {
      writeStoredAccountId(3)
    }).not.toThrow()
  })

  it('uses the remembered account if it exists, else the lowest id', () => {
    const accounts = [account(3), account(1), account(2)]
    expect(resolveAccountId(accounts, 2)).toBe(2)
    expect(resolveAccountId(accounts, 99)).toBe(1)
    expect(resolveAccountId(accounts, null)).toBe(1)
    expect(resolveAccountId([], 2)).toBeNull()
  })

  it('FEAT-035: remembers 全部账户 as ALL (Q219)', () => {
    writeStoredAccountId('ALL')
    expect(window.localStorage.getItem(ACCOUNT_STORAGE_KEY)).toBe('ALL')
    expect(readStoredAccountId()).toBe('ALL')
    window.localStorage.setItem(ACCOUNT_STORAGE_KEY, 'all')
    expect(readStoredAccountId()).toBeNull()
  })

  it('FEAT-035: keeps ALL only with at least 2 accounts (Q220), else the lowest id', () => {
    expect(resolveAccountId([account(2), account(1)], 'ALL')).toBe('ALL')
    expect(resolveAccountId([account(2)], 'ALL')).toBe(2)
    expect(resolveAccountId([], 'ALL')).toBeNull()
    expect(canSelectAllAccounts([account(1)])).toBe(false)
    expect(canSelectAllAccounts([account(1), account(2)])).toBe(true)
  })
})
