# Testing and Verification Log

## Crowdsourced Mapping & POI Refinement Subsystem

**Project:** IndoorPulse – Indoor Navigation System  
**Course:** UCS503 – Software Engineering  
**Contributor:** Pooja  
**Date:** 24 November 2026  
**Branch:** pooja-crowdsourced-mapping

## 1. Verification Environment

- Operating System: Windows
- Development Environment: Visual Studio Code
- Terminal: PowerShell
- Version Control: Git and GitHub
- Verification Method: Repository inspection and static source-code review

## 2. Repository Verification

| Verification | Observed Result | Status |
|---|---|---|
| Local repository | IndoorPulse-Android | Verified |
| Individual branch | pooja-crowdsourced-mapping | Verified |
| Remote repository | rizwanhanda/IndoorPulse-Android | Verified |
| Initial working tree | Clean | Verified |

## 3. Documentation Verification

The documentation directory was inspected using PowerShell.

| File | Observed Size | Status |
|---|---:|---|
| README.md | 1839 bytes | Verified |
| Technical-Documentation.md | 3396 bytes | Verified |
| Test-Cases.md | 3733 bytes | Verified |
| Weekly-Journal.md | 2547 bytes | Verified |

All four files were present and contained saved content at the time of inspection.

## 4. Static Source-Code Verification

The relevant functions were located in MapViewModel.kt using Git search commands.

| Function | Line Number | Observation |
|---|---:|---|
| updateNodeType() | 224 | Function located |
| linkNodes() | 702 | Function located |
| forceDropNewNode() | 753 | Function located |
| exportMapToJson() | 1595 | Function located |
| importMapFromJson() | 1675 | Function located |

Function discovery confirms the presence of relevant source-code declarations but does not establish successful runtime execution.

## 5. Dynamic Obstacle Reporting

Search command:

`git grep -n -i "obstacle" -- app/src/main`

Observed result: No matching references were returned.

Conclusion: Dynamic obstacle reporting requires further implementation verification.

## 6. Backend Synchronization

Search command:

`git grep -n -i -E "retrofit|firebase|syncMap|uploadMap|syncNodes" -- app/src/main`

Observed result: No matching references were returned.

Conclusion: Backend synchronization could not be confirmed through the selected keywords. Further investigation is required.

## 7. Functional Testing Summary

| Metric | Count |
|---|---:|
| Functional test cases prepared | 13 |
| Runtime tests executed | 0 |
| Runtime tests passed | 0 |
| Runtime tests failed | 0 |
| Tests awaiting execution | 11 |
| Tests awaiting implementation verification | 2 |

## 8. Limitations

- Runtime testing has not yet been performed.
- Android device or emulator validation remains pending.
- Dynamic obstacle reporting requires further investigation.
- Backend synchronization requires further verification.
- Static source-code inspection does not guarantee functional correctness.

## 9. Conclusion

Repository verification, documentation verification, and initial static source-code inspection have been completed.

Thirteen functional test cases have been prepared for future execution. All observations reflect the actual verification performed, without claiming unexecuted tests as successful.

Existing application source code was not modified during this documentation contribution.
## Actual Build and Unit Testing Results

### Verification Date
24 November 2026

### Environment Configuration
- Operating System: Windows 11
- Java: JDK 17
- Gradle: 9.8.0
- Kotlin: 2.4.10
- Gradle User Home: D:\GradleHome
- Additional virtual memory configured on D drive.

### Android Build Verification
- Build command: assembleDebug
- Build status: SUCCESSFUL
- Build duration: 1 minute 59 seconds
- Total actionable tasks: 38
- APK generated: app-debug.apk
- APK size: 64,327,990 bytes

### Unit Testing Results
- Test command: testDebugUnitTest
- Test class: com.indoorpulse.ExampleUnitTest
- Total tests: 18
- Passed: 18
- Failed: 0
- Skipped: 0
- Success rate: 100%
- Gradle execution time: 50 seconds
- Test report duration: 3.055 seconds

### Relevant Mapping Test Coverage
- Node selection radius
- Normal waypoint placement
- Same-floor node validation
- Cross-floor connection validation
- Vertical meshing and edge alignment
- Smart portal snapping
- Floor-aware nearest-neighbor filtering

### Technical Troubleshooting
- Resolved insufficient disk space during build by relocating Gradle storage to D drive.
- Configured additional Windows virtual memory.
- Limited Gradle workers and used in-process Kotlin compilation.
- Successfully completed the Android build and existing unit test suite without modifying application source code.

### Testing Limitations
The 18 existing unit tests were executed and verified successfully. The 13 independently designed functional test cases remain pending manual execution. Complete end-to-end subsystem functionality has not yet been verified.

### Evidence
- Android-Build-Success.png
- Android-Unit-Tests.png
- Android-Test-Report.png
- Android-Test-Details-1.png
- Android-Test-Details-2.png

Evidence screenshots are maintained separately in the local project evidence folder.
