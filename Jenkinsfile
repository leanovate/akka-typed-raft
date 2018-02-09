pipeline {
  agent any
  stages {
    stage('Unit Test') {
      steps {
        sh '/var/jenkins_home/bin/sbt test'
      }
    }
  }
}