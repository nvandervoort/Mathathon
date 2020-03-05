## Python server (MathathonServer)

#### Requirements
* Python3
* Modules avail. through pip: tqdm, urllib3, and netifaces
* Selected port must be exactly four digits

IP address will be displayed when program is run

#### Usage:
1. From terminal run  
`python3 MathathonServer.py {port} {num_questions} {difficulty[1-2]}`
2. Type `start` and press enter when all clients have joined

## Android Client Application (Mathathon)

Works best in Android emulator. Dependencies are detailed in .gradle build file and should download automatically before buildingwhoever answers the most questions correctly in the given time limit wins. The clients (Android) will be notified of winning or losing when game ends.

#### Usage
1. Enter IP address printed by server program and port used to start it
2. Android app will check validity of full address and allow player to join if it is valid
3. After joining, the client will wait for the notification to start the game and then countdown
4. Players can submit answers for each question or skip the question