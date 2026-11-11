# DeployFlow

DeployFlow is a small Java 21 REST service delivered through a Maven, Jenkins, Docker, and AWS EC2 pipeline. A push to GitHub starts a Jenkins build, runs the test suite, creates a versioned image, and replaces the running application container.

> This is a portfolio-scale, single-host deployment. Jenkins and the app share one EC2 instance, and replacing the container causes a short outage. Do not treat this setup as highly available or expose an unauthenticated Jenkins controller to the public internet.

## Architecture

```text
Developer
   | git push
   v
GitHub repository -- webhook --> Jenkins on EC2 (:8080)
                                      |
                                Maven build + tests
                                      |
                                Docker image
                                      |
                              Docker on EC2 host
                                      |
Client --------------------------> :8081 -> container :8080
                                      |
                          Spring Boot REST API
                            /api/health /api/info
```

## API

| Method | Path | Example response |
| --- | --- | --- |
| `GET` | `/api/health` | `{"status":"UP","version":"1.0.0"}` |
| `GET` | `/api/info` | App name, description, version, and Java feature version |

The service listens on port `8080` inside the container. Docker maps the EC2 host's `8081` to that container port. For example, `-p 8081:8080` means requests to `EC2_PUBLIC_IP:8081` are forwarded to port `8080` in the container; it does not change Spring Boot's listening port.

## Local Build and Run

Prerequisites: JDK 21 and Maven 3.9+.

```bash
mvn clean test
mvn clean package
java -jar target/deployflow.jar
```

Then check `http://localhost:8080/api/health` and `http://localhost:8080/api/info`.

Build and run the container locally:

```bash
docker build -t deployflow:local .
docker run --rm --name deployflow-local -p 8081:8080 deployflow:local
```

In another terminal, open `http://localhost:8081/api/health`.

## Phase 1: Application and Tests

The application is a Spring Boot 3.4.x Maven project targeting Java 21. `ApplicationTests` exercises both REST endpoints through Spring's MockMvc test support. Run `mvn test` to execute these tests. The test stage in Jenkins publishes the resulting Surefire XML reports.

## Phase 2: Container Image

The `Dockerfile` has two stages: Maven compiles the executable JAR, then a smaller Temurin JRE Alpine image runs only that JAR as a non-root user. `.dockerignore` excludes local build output and repository/editor files from the build context.

The published port is `8081:8080`: left side is the host port, right side is the container port. The EC2 security group must also allow inbound traffic to host port `8081` for clients to reach the API.

## Phase 3: Jenkins Pipeline

The root `Jenkinsfile` checks out the configured SCM, packages the app, runs tests, builds `deployflow:<BUILD_NUMBER>`, and starts it under the stable container name `deployflow`. A failed checkout, build, test, or image build prevents deployment. The pipeline requires `mvn` and `docker` on the Jenkins agent's `PATH` and the Jenkins service account's permission to access Docker.

The deployment uses `docker rm -f` before `docker run`, as requested for this portfolio workflow. It briefly takes the old version offline and does not provide automatic rollback if the new container fails to start. For production, use a health-checked blue/green or rolling deployment behind a load balancer, image retention/rollback, centralized logs, and a dedicated deployment identity.

## Phase 4: AWS EC2 Setup

### Launch the instance

1. In the AWS Console, open **EC2 > Launch instance**. Select an Ubuntu Server 24.04 LTS AMI and an instance type that the console currently marks as Free Tier eligible for your account and region. Free Tier offers and eligibility can change; verify the estimate before launch.
2. Create or select an SSH key pair and download the private key once. Keep it private and set restrictive permissions on your computer.
3. Create a security group with these inbound rules:

| Type | Port | Source | Purpose |
| --- | ---: | --- | --- |
| SSH | 22 | My IP (`/32`) | Administrator access |
| Custom TCP | 8080 | Prefer a trusted IP/VPN; see webhook note below | Jenkins UI and GitHub webhook |
| Custom TCP | 8081 | The client IPs that need the demo API | Spring Boot API |

Avoid `0.0.0.0/0` for SSH. GitHub must be able to reach the webhook endpoint. Prefer putting Jenkins behind HTTPS on port 443 and restricting access with a reverse proxy/firewall policy; if testing the plugin directly on port 8080, expose it only as long as needed and protect Jenkins with authentication and current updates. The webhook note below explains this tradeoff. Do not expose Jenkins over unauthenticated plain HTTP.

4. Choose a small root EBS volume (for example, 8 GiB gp3), launch the instance, and note its public IPv4 address. Confirm the attached security group and that the instance has outbound internet access for package installation and GitHub.
5. Connect from your machine using the downloaded key:

```bash
ssh -i /path/to/key.pem ubuntu@EC2_PUBLIC_IP
```

### Install Java, Maven, Docker, and Jenkins

Run the following on the Ubuntu instance. The Docker repository commands use the official Docker apt repository; Jenkins is installed from its Debian stable repository.

```bash
sudo apt-get update
sudo apt-get install -y fontconfig openjdk-21-jre maven ca-certificates curl
java -version
mvn -version
```

Install Docker Engine:

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo \"${UBUNTU_CODENAME:-$VERSION_CODENAME}\") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker run --rm hello-world
```

Install Jenkins LTS:

```bash
sudo curl -fsSL https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key -o /usr/share/keyrings/jenkins-keyring.asc
echo "deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] https://pkg.jenkins.io/debian-stable binary/" | sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null
sudo apt-get update
sudo apt-get install -y jenkins
sudo systemctl enable --now jenkins
```

Jenkins must be able to invoke Docker. Add its service account to the Docker group, then restart Jenkins so the new supplementary group is applied:

```bash
sudo usermod -aG docker jenkins
sudo systemctl restart jenkins
sudo systemctl status jenkins --no-pager
```

Membership in the `docker` group effectively grants root-level control of the host. This is acceptable only for a dedicated learning instance; do not share that host with untrusted users or workloads. Maven is installed system-wide so the Jenkins agent can run the exact `mvn` commands in the pipeline.

Retrieve the initial Jenkins unlock password on the instance:

```bash
sudo cat /var/lib/jenkins/secrets/initialAdminPassword
```

Open `http://EC2_PUBLIC_IP:8080` only from a trusted network, unlock Jenkins, install the suggested plugins, and create an administrator account. Confirm that Git, Pipeline, JUnit, and the GitHub plugin are installed. Install the GitHub plugin through **Manage Jenkins > Plugins** if it is missing; the `githubPush()` trigger depends on it.

## Phase 5: GitHub and Resume Readiness

### Create the Jenkins job

1. Create an empty repository on GitHub (do not initialize it with a README or license), then from this project directory run:

      ```bash
      git init -b main
      git add .
      git commit -m "Initial DeployFlow pipeline"
      git remote add origin https://github.com/<GITHUB_USER>/<REPOSITORY>.git
      git push -u origin main
      ```

2. In Jenkins, create a **Pipeline** job. Under **Pipeline**, select **Pipeline script from SCM**, choose **Git**, enter the repository URL, select the branch (usually `*/main`), and set the script path to `Jenkinsfile`.
3. For a private repository, add a Jenkins credential with the least privilege needed to clone it and select that credential in the SCM configuration. Do not put tokens in the `Jenkinsfile` or repository.
4. Save the job and run it once manually. Check the console log and JUnit report before enabling the webhook.

### Configure the GitHub webhook

1. In the GitHub repository, open **Settings > Webhooks > Add webhook**.
2. Set **Payload URL** to `http://EC2_PUBLIC_IP:8080/github-webhook/`, or preferably the equivalent HTTPS URL on a domain terminated by a trusted reverse proxy.
3. Set **Content type** to `application/json`, configure a webhook secret if your Jenkins GitHub plugin setup supports and validates one, and select **Just the push event**.
4. Add the webhook, then push a small commit. In GitHub's webhook page, inspect **Recent Deliveries** for a successful response; in Jenkins, confirm a new build started.

The webhook endpoint must be reachable from GitHub. Opening port 8080 to all IPv4 addresses makes it reachable but also exposes the Jenkins login surface. For anything beyond a short-lived demo, use HTTPS and a reverse proxy, keep Jenkins patched and authenticated, and apply an IP allowlist/firewall policy that accounts for GitHub's published webhook address ranges. Recheck those ranges rather than hard-coding stale addresses. If your Jenkins is private-only, use a controlled relay or polling instead of making the controller public.

### Resume framing

Describe this accurately as a single-node CI/CD portfolio deployment: Spring Boot REST endpoints, Maven/JUnit verification, a multi-stage non-root Docker image, Jenkins Declarative Pipeline, GitHub push integration, and EC2-hosted Docker deployment. Be ready to explain the security group, Docker group privilege, test gating, host-to-container port mapping, and the current downtime/rollback limitations. Avoid claiming high availability, zero downtime, or production hardening that this implementation does not provide.

## Troubleshooting

| Symptom | Checks |
| --- | --- |
| `mvn: command not found` in Jenkins | Confirm Maven is installed and available to the Jenkins service `PATH`; restart Jenkins after environment changes. |
| Docker permission denied | Check `id jenkins` includes `docker`, then restart Jenkins. Remember Docker group access is root-equivalent. |
| Jenkins webhook gets no response | Confirm the instance is running, the webhook URL ends in `/github-webhook/`, port 8080 is reachable, and the GitHub plugin is installed. Inspect GitHub **Recent Deliveries** and Jenkins logs. |
| API times out externally | Check that the container is running (`sudo docker ps`), its logs (`sudo docker logs deployflow`), the `8081:8080` mapping, and the EC2 security group rule for port 8081. |
| `port is already allocated` | Find the process/container using host port 8081 (`sudo docker ps` and `sudo ss -ltnp`); resolve the conflict before deploying. |
| Build fails downloading dependencies | Verify outbound network/DNS from EC2 and Maven Central availability, then inspect the first Maven error in the Jenkins console log. |

Useful host commands:

```bash
sudo docker ps
sudo docker logs --tail 100 deployflow
sudo journalctl -u jenkins -n 100 --no-pager
```