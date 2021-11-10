Feature: Association

  Background: Association setup
    Given Alice has a new account
    And Bob has a new account

  Scenario: Successful association transaction
    Given Alice has 10 lto
    When Alice make an association with Bob of type 1
    Then Alice is associated with Bob
    And Alice has 9 lto

  Scenario: Successful revoke association transaction
    Given Alice has 10 lto
    And Alice has an association with Bob of type 1
    When Alice revoke the association with Bob of type 1
    Then Alice is not associated with Bob
    And Alice has 8 lto

  Scenario: Unsuccessful association transaction due to insufficient balance
    Given Alice has 0 lto
    When Alice tries to make an association with Bob of type 1
    Then The transaction fails

  Scenario: Issue, revoke, issue association
    Given Alice has an association with Bob of type 5
    And Alice has 10 lto
    When Alice revoke the association with Bob of type 5
    And Alice tries to make an association with Bob of type 5
    Then Alice has 8 lto
    And Alice is not associated with Bob

  Scenario: Revoke association with anchor
    Given Alice has an association with Bob of type 76 and anchor qwerty
    And Alice has 10 lto
    When Alice revoke the association with Bob of type 76 and anchor qwerty
    Then Alice has 9 lto
    And Alice is not associated with Bob