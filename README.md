\# CrashGuard — Two-Wheeler Crash Detection System



CrashGuard is an Android-based research prototype designed to detect potential two-wheeler crashes using smartphone motion sensors, a multi-stage detection pipeline, camera-based verification, and configurable emergency SMS alerts.



The project explores how accelerometer and gyroscope data can help distinguish unusual motion from potential crash events. It combines machine-learning-based anomaly detection with additional crash-signature checks and recovery logic to reduce false alarms.



> \*\*Disclaimer:\*\* CrashGuard is an experimental prototype, not a certified safety device. It may miss crashes or generate false alarms. Do not rely on it as a substitute for dedicated safety equipment or emergency services.



\## Features



\- \*\*Motion sensor monitoring:\*\* Reads accelerometer and gyroscope data from the Android device.

\- \*\*Sensor data recording:\*\* Records timestamped sensor measurements into CSV session files for analysis and experimentation.

\- \*\*Stage 1 — Anomaly detection:\*\* Uses a machine-learning model to identify motion patterns that differ from the benign riding data used during development.

\- \*\*Stage 2 — Crash-signature verification:\*\* Applies additional checks to determine whether an anomalous event resembles a potential crash.

\- \*\*Recovery-state logic:\*\* Evaluates subsequent motion to help suppress false alarms from temporary disturbances.

\- \*\*Camera-based verification:\*\* Captures a short evidence sequence after a potential incident and sends selected frames for Gemini Vision classification when configured.

\- \*\*Incident notifications:\*\* Provides local Android notifications for confirmed incidents.

\- \*\*Emergency SMS alerts:\*\* Supports a configurable emergency contact and an SMS alert workflow.

\- \*\*Monitoring and diagnostics:\*\* Provides an interface for sensor readings, recording controls, and diagnostic information.

\- \*\*Incident history:\*\* Supports viewing recorded incident information within the application.



Feature availability may depend on device capabilities, runtime permissions, network connectivity, and configuration.



\## System Architecture



The application follows a multi-stage approach rather than treating every unusual sensor reading as a crash.





flowchart TD

&#x20;   A\["Accelerometer and Gyroscope"] --> B\["Sensor Data Processing"]

&#x20;   B --> C\["Stage 1: ML Anomaly Detection"]

&#x20;   C --> D\["Stage 2: Crash-Signature Checks"]

&#x20;   D --> E\["Recovery-State Evaluation"]

&#x20;   E --> F\["Potential Incident Trigger"]

&#x20;   F --> G\["Camera Evidence Collection"]

&#x20;   G --> H\["Gemini Vision Verification"]

&#x20;   H --> I\["Incident Handling and Alerting"]

&#x20;   I --> J\["Local Notification"]

&#x20;   I --> K\["Emergency SMS"]



&#x20;   classDef sensor fill:#dbeafe,stroke:#2563eb,color:#111827

&#x20;   classDef detection fill:#fef3c7,stroke:#d97706,color:#111827

&#x20;   classDef alert fill:#dcfce7,stroke:#16a34a,color:#111827



&#x20;   class A,B sensor

&#x20;   class C,D,E,F,G,H detection

&#x20;   class I,J,K alert





The diagram describes the intended high-level workflow. Exact execution and failure-handling behavior depend on the implemented application state and configuration. Camera verification is performed after a potential incident is triggered; it does not provide pre-trigger video footage.



\### Detection stages



\*\*1. Sensor acquisition\*\*



The application obtains accelerometer and gyroscope measurements from the phone. Timestamped data can be recorded for offline analysis and model development.



\*\*2. Machine-learning anomaly detection\*\*



The first stage identifies unusual motion relative to the benign data used to develop the detector. An anomaly is not necessarily a crash: road bumps, phone movement, and other disturbances can also produce unusual readings.



\*\*3. Crash-signature verification\*\*



The second stage evaluates additional motion characteristics to determine whether an anomaly is consistent with the crash patterns targeted by the prototype.



\*\*4. Recovery evaluation\*\*



The recovery logic considers subsequent motion to help distinguish a continuing incident from a transient disturbance.



\*\*5. Camera and vision verification\*\*



When triggered and permitted, the application captures a short sequence of camera frames and can submit selected frames to Gemini Vision. A vision result is not infallible. API errors or inconclusive responses must not be interpreted as proof that a crash occurred or did not occur.



\*\*6. Incident response\*\*



The application can issue a local notification and supports configurable emergency SMS functionality. Successful alert delivery depends on the applicable permissions, device configuration, network or cellular service, and the actual execution path.



\## Technology Stack



| Component | Technology |

|---|---|

| Programming language | Kotlin |

| Android interface | Jetpack Compose |

| Platform | Android SDK |

| Motion sensing | Android accelerometer and gyroscope APIs |

| Machine learning | Isolation Forest-based anomaly detection pipeline |

| Model integration | ONNX Runtime, where configured in the application |

| Camera | CameraX |

| Vision verification | Gemini Vision API |

| Build system | Gradle with Kotlin DSL |

| Automated tests | Android/JVM unit-test infrastructure |



\## Repository Structure



```text

CrashGuard/

├── app/

│   └── src/

│       ├── main/

│       │   ├── java/       # Android application source

│       │   ├── res/        # Android resources

│       │   └── assets/     # Bundled model/assets, if configured

│       └── test/           # Unit tests

├── gradle/                 # Gradle wrapper configuration

├── build.gradle.kts        # Root build configuration

├── settings.gradle.kts     # Project/module configuration

├── gradle.properties       # Gradle properties

├── gradlew                 # Gradle wrapper for Unix-like systems

├── gradlew.bat             # Gradle wrapper for Windows

├── .gitignore

└── README.md

```



The exact source files and assets may change as the project develops.



\## Requirements



\- Android Studio with a compatible Android SDK and Gradle/JDK setup.

\- An Android device with a compatible accelerometer and gyroscope for sensor testing.

\- Camera permission and camera hardware for camera verification.

\- Gemini API configuration and internet connectivity for online vision verification.

\- Appropriate SMS permissions and a compatible device/SIM configuration for emergency SMS testing.



The project's Android configuration uses `minSdk 26` and compile/target SDK 36. Check the Gradle configuration if using a different revision.



\## Setup and Installation



\### 1. Clone the repository



```bash

git clone https://github.com/Malekith827/CrashGuard.git

cd CrashGuard

```



\### 2. Open the project



Open the cloned `CrashGuard` directory in Android Studio and allow Gradle synchronization to complete.



\### 3. Configure secrets



Configure the Gemini API key using the local configuration mechanism expected by the application. Keep private credentials out of source control.



\- Do not commit `local.properties` if it contains secrets.

\- Do not hardcode API keys in Kotlin files.

\- A key embedded in an Android APK can potentially be extracted. For production use, move privileged API access behind a secured backend and apply appropriate access restrictions.

\- Review `.gitignore` before committing configuration files.



\### 4. Build the application



On Windows PowerShell:



```powershell

.\\gradlew.bat assembleDebug

```



\### 5. Run unit tests



```powershell

.\\gradlew.bat testDebugUnitTest

```



To rerun the debug unit tests:



```powershell

.\\gradlew.bat testDebugUnitTest --rerun-tasks

```



\### 6. Install and test on a device



Run the application from Android Studio on a compatible Android phone. Grant only the permissions required for the features being tested.



Test sensor recording, detection behavior, camera access, notification behavior, and SMS functionality separately. Use controlled, safe test scenarios; do not simulate a motorcycle crash on a public road.



\## Data Collection and Evaluation



The project uses smartphone motion data for experimentation with crash-related event detection. Data analysis and model evaluation should distinguish between individual sensor windows and complete physical event trials.



Important evaluation practices include:



\- Split training and evaluation data by recording session where appropriate, rather than randomly splitting adjacent sensor rows.

\- Evaluate crash events separately from non-crash disturbances.

\- Report false positives and missed events, not only aggregate accuracy.

\- Record the detection configuration and thresholds used for each experiment.

\- Keep uncertain or overlapping labels separate when their ground truth cannot be established reliably.

\- Test across different phone orientations, mounting positions, and motion conditions.



A model anomaly score is not a calibrated crash probability. Results from a small experimental dataset should not be treated as evidence of reliable real-world crash detection.



\## Testing



The project includes automated unit tests and supports manual device testing.



Suggested test categories:



1\. Sensor acquisition and recording.

2\. Feature extraction and anomaly scoring.

3\. Crash-signature decision logic.

4\. Recovery-state transitions.

5\. Camera capture and vision-result handling.

6\. Handling of unavailable network services and inconclusive vision responses.

7\. Notification generation.

8\. Emergency contact configuration and SMS behavior.

9\. Permission denial and lifecycle handling.



Record actual test outputs and device observations in the project report. Do not infer successful end-to-end crash detection merely because individual components or a manual notification test work.



\## Privacy and Safety



\- Sensor recordings may reveal movement patterns and should be handled responsibly.

\- Camera evidence may contain identifiable people or surroundings. Collect and share it only when appropriate.

\- Protect API credentials and avoid uploading secrets to GitHub.

\- Verify permissions and emergency contact settings before testing alerts.

\- Test emergency messaging with consent and avoid sending unnecessary messages to real emergency services.

\- Treat network failures and uncertain model outputs as unresolved outcomes.

\- CrashGuard has not been established as a certified or safety-critical crash detection system.



\## Current Limitations



\- Sensor readings vary with phone orientation, mounting position, device hardware, and environmental conditions.

\- Anomaly detection can flag non-crash events and may miss genuine crashes.

\- Model performance depends on the coverage and quality of training and evaluation data.

\- Camera verification occurs after the trigger and does not supply pre-trigger footage.

\- Gemini Vision depends on external API availability and network connectivity.

\- SMS behavior depends on device support, permissions, and the implemented alert path.

\- Experimental results do not guarantee reliable operation across all riders, motorcycles, or road conditions.



\## Future Improvements



\- Expand the dataset with diverse, carefully labelled riding sessions.

\- Evaluate detection using held-out sessions and physical event-level metrics.

\- Improve calibration and reduce false alarms without increasing missed events.

\- Strengthen automated tests for the complete incident-to-alert workflow.

\- Improve secure API access and error handling.

\- Evaluate power consumption, background execution, and long-duration sensor recording.

\- Conduct controlled field evaluation before making real-world reliability claims.



\## Author and Project



\*\*Project:\*\* CrashGuard — Two-Wheeler Crash Detection System  

\*\*Repository:\*\* https://github.com/Malekith827/CrashGuard



This project is an experimental academic software prototype intended for learning, development, and evaluation of sensor-based crash detection techniques.



