#!/bin/sh

gcloud compute instances list | grep akka-node | awk ' { print $4," ",$1 } '