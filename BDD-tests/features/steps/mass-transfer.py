from behave import *
import tools

@when(u'{sender} tries to do a mass-transfer of {amount1} lto to {receiver1} and {amount2} lto to {receiver2}')
def step_impl(context, sender, amount1, receiver1, amount2, receiver2):
    transfers = [[receiver1, amount1], [receiver2, amount2]]
    try:
        tools.massTransfer(transfers, sender)
    except:
        pass

@when(u'{sender} does a mass-transfer of {amount1} lto to {receiver1} and {amount2} lto to {receiver2}')
def step_impl(context, sender, amount1, receiver1, amount2, receiver2):
    transfers = [[receiver1, amount1], [receiver2, amount2]]
    tools.massTransfer(transfers, sender)
