import lto
from behave import *
from e2e.common.tools import NODE, broadcast, convert_balance, funds_for_transaction, minimum_balance
from lto.transactions import Lease, CancelLease


def is_leasing(context, account1, account2, amount=""):
    account1 = context.users[account1]
    account2 = context.users[account2]
    lease_list = NODE.lease_list(account1.address)
    leases = []
    for lease in lease_list:
        if lease['recipient'] == account2.address:
            if amount:
                if lease['amount'] == amount:
                    leases.append(lease)
            else:
                leases.append(lease)
    return leases


def get_lease_id(context, account1, account2):
    lease_list = NODE.lease_list(account1.address)
    for lease in lease_list:
        if lease['recipient'] == account2.address:
            return lease['id']
    raise Exception("No Lease Id Found")


def cancel_lease(context, account1, account2, version=None):
    account1 = context.users[account1]
    account2 = context.users[account2]

    lease_id = get_lease_id(context, account1, account2)
    transaction = CancelLease(lease_id)
    transaction.version = version or CancelLease.DEFAULT_VERSION
    transaction.sign_with(account1)
    broadcast(context, transaction)


def lease(context, account1, account2, amount="", version=None):
    if not amount:
        amount = 100000000
    amount = int(amount)
    account1 = context.users[account1]
    account2 = context.users[account2]

    transaction = Lease(recipient=account2.address, amount=amount)
    transaction.version = version or Lease.DEFAULT_VERSION
    transaction.sign_with(account1)
    broadcast(context, transaction)


@given('{user1} is not leasing to {user2}')
def step_impl(context, user1, user2):
    if is_leasing(context, user1, user2):
        funds_for_transaction(context, user1, CancelLease.BASE_FEE)
        cancel_lease(context, user1, user2)


@when('{user1} tries to cancels the lease to {user2}')
def step_impl(context, user1, user2):
    try:
        cancel_lease(context, user1, user2)
    except:
        context.last_tx_success = False


@given('{user1} is leasing {amount} lto to {user2}')
def step_impl(context, user1, amount, user2):
    amount = convert_balance(amount)
    if not is_leasing(context, user1, user2, amount):
        minimum_balance(context, user1, amount)
        funds_for_transaction(context, user1, Lease.BASE_FEE)
        lease(context, user1, user2, amount)


@when('{user1} leases {amount} lto to {user2}')
@when('{user1} leases (v{version:d}) {amount} lto to {user2}')
def step_impl(context, user1, amount, user2, version=None):
    amount = convert_balance(amount)
    lease(context, user1, user2, amount, version)


@when('{user1} cancels the lease to {user2}')
@when('{user1} cancels the lease (v{version:d}) to {user2}')
def step_impl(context, user1, user2, version=None):
    cancel_lease(context, user1, user2, version)


@when('{user1} tries to lease {amount} lto to {user2}')
def step_impl(context, user1, amount, user2):
    try:
        lease(context, user1, user2, amount)
    except:
        pass


@then('{user1} is leasing {amount} lto to {user2}')
def step_impl(context, user1, amount, user2):
    amount = convert_balance(amount)
    value = is_leasing(context, user1, user2, amount)
    assert value, f'{user1} is not leasing to {user2}'


@then('{user1} is not leasing to {user2}')
def step_impl(context, user1, user2):
    value = is_leasing(context, user1, user2)
    assert not value, f'{value}'
