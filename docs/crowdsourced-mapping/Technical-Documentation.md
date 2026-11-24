# Technical Documentation

## Crowdsourced Mapping & POI Refinement Subsystem

**Project:** IndoorPulse – Indoor Navigation System  
**Course:** UCS503 – Software Engineering  
**Contributor:** Pooja

## 1. Introduction

The Crowdsourced Mapping and POI Refinement subsystem is responsible for supporting indoor map expansion, pathway recording, Point of Interest management, obstacle reporting, and mapping data synchronization.

## 2. Technical Architecture

The Android application follows the MVVM (Model-View-ViewModel) architecture.

Relevant technologies include:

- Kotlin programming language
- Android ViewModel
- Kotlin Coroutines
- Room Database
- Data Access Objects (DAO)
- JSON-based map import and export

## 3. Relevant Source File

`app/src/main/java/com/indoorpulse/presentation/viewmodel/MapViewModel.kt`

## 4. Existing Implementation Analysis

### 4.1 Record Unmapped Pathway

**Relevant functions:**

- `forceDropNewNode()` — Line 753
- `linkNodes()` — Line 702

The node creation function uses the estimated user position and generates a unique identifier for a new mapping point. Node linking supports the representation of connections between mapping points.

### 4.2 Tag and Register Shop POI

**Relevant functions:**

- `forceDropNewNode()` — Line 753
- `updateNodeType()` — Line 224

The node creation function accepts a node name and node type. It includes logic for handling user-provided names and automatically generating labels when a name is not provided.

The node type update function is relevant to POI classification.

### 4.3 Report Dynamic Obstacle

Dynamic obstacle reporting is part of the assigned subsystem requirements.

The corresponding implementation and its integration with navigation require further verification.

### 4.4 Mapping Data Transfer

**Relevant functions:**

- `exportMapToJson()` — Line 1595
- `importMapFromJson()` — Line 1675

These functions are relevant to map data import and export using JSON.

Backend synchronization must be verified separately, as JSON import and export alone do not establish server synchronization.

## 5. Data Representation

- Node: A mapping point representing an indoor location.
- Edge: A connection between two mapping points.
- POI: A Point of Interest, such as a shop or facility.
- UUID: A unique identifier assigned to a mapping point.
- Floor: The indoor level associated with mapping information.

## 6. Implementation Review Status

| Component | Review Status |
|---|---|
| Node creation | Relevant implementation identified |
| Pathway linking | Relevant function identified |
| POI classification | Relevant function identified |
| Dynamic obstacle reporting | Verification pending |
| JSON import and export | Relevant functions identified |
| Backend synchronization | Verification pending |
| Runtime testing | Pending |

## 7. Individual Contribution

The individual contribution includes subsystem documentation, source code analysis, functional test case preparation, and identification of areas requiring further validation.

## 8. Conclusion

The identified mapping functions provide a foundation for reviewing the Crowdsourced Mapping and POI Refinement subsystem. Further testing and implementation verification are required to establish complete functional coverage.
## Contribution Scope and Acknowledgement

The original application, algorithms, source code and existing unit tests were developed by the project leader and respective team contributors.

This individual contribution consists of AI-assisted documentation, existing source-code review, test case preparation, environment configuration, build verification and execution of existing unit tests.

The documented subsystem requirements and proposed test cases do not establish that every feature has been implemented or verified.

No authorship of the original application source code or existing unit tests is claimed.

The latest verification results are recorded in Testing-Log.md. Individual documentation commits are maintained on a personal GitHub fork.

