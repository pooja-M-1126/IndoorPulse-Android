# Functional Test Cases

## Crowdsourced Mapping & POI Refinement Subsystem

**Project:** IndoorPulse – Indoor Navigation System  
**Course:** UCS503 – Software Engineering  
**Contributor:** Pooja  
**Test Type:** Functional Testing

## 1. Testing Objective

To validate the functionality, reliability, and data consistency of the Crowdsourced Mapping and POI Refinement subsystem.

## 2. Record Unmapped Pathway

### TC-01: Create a New Mapping Node

- Precondition: Mapping mode is available.
- Action: Add a new mapping point at the estimated position.
- Expected Result: A new node is created with a unique identifier.
- Status: Not Executed.

### TC-02: Connect Two Mapping Nodes

- Precondition: Two valid mapping nodes exist.
- Action: Connect the two nodes.
- Expected Result: A valid edge is created between the nodes.
- Status: Not Executed.

### TC-03: Prevent Invalid Node Connections

- Action: Attempt to connect a node to itself.
- Expected Result: The invalid connection is rejected.
- Status: Not Executed.

## 3. Tag and Register Shop POI

### TC-04: Register a Shop

- Input: Node Name = Zara, Node Type = Shop.
- Expected Result: A new shop POI is created with the specified name and category.
- Status: Not Executed.

### TC-05: Handle Duplicate Node Names

- Precondition: A node named Zara already exists.
- Action: Create another node with the same name.
- Expected Result: A distinguishable node label is generated.
- Status: Not Executed.

### TC-06: Handle Blank Node Name

- Input: Empty node name and Shop node type.
- Expected Result: A default node label is generated automatically.
- Status: Not Executed.

### TC-07: Remove Unnecessary Spaces

- Input: A node name containing leading and trailing spaces.
- Expected Result: Unnecessary spaces are removed from the node name.
- Status: Not Executed.

### TC-08: Update POI Category

- Action: Change the category of an existing node.
- Expected Result: The updated category is reflected in the mapping data.
- Status: Not Executed.

## 4. Report Dynamic Obstacle

### TC-09: Report a Temporary Obstacle

- Action: Submit an obstacle report for an indoor pathway.
- Expected Result: The obstacle information is recorded and made available to the relevant navigation component.
- Status: Implementation Verification Pending.

## 5. Map Data Transfer and Synchronization

### TC-10: Export Mapping Data

- Action: Export existing map data.
- Expected Result: A valid JSON representation of the mapping data is generated.
- Status: Not Executed.

### TC-11: Import Mapping Data

- Action: Import a valid map JSON file.
- Expected Result: Supported mapping information is restored correctly.
- Status: Not Executed.

### TC-12: Handle Invalid JSON

- Action: Attempt to import an invalid JSON file.
- Expected Result: The application handles the invalid input without crashing.
- Status: Not Executed.

### TC-13: Backend Data Synchronization

- Action: Submit updated mapping information for synchronization.
- Expected Result: Supported changes are synchronized with the backend and database.
- Status: Implementation Verification Pending.

## 6. Test Case Summary

| Category | Test Cases |
|---|---:|
| Pathway Recording | 3 |
| POI Registration and Refinement | 5 |
| Dynamic Obstacle Reporting | 1 |
| Data Transfer and Synchronization | 4 |
| Total | 13 |

## 7. Testing Status

- Total test cases prepared: 13
- Tests executed: 0
- Tests passed: 0
- Tests failed: 0
- Tests awaiting execution: 11
- Tests awaiting implementation verification: 2

Actual test execution results will be recorded separately in Testing-Log.md.