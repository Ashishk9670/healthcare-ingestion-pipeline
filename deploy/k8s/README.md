# Kubernetes manifests

Plain manifests plus a kustomization (`kubectl apply -k deploy/k8s`). Postgres, Kafka and Keycloak are assumed to be
provided by your platform (hostnames in `configmap.yaml` are placeholders). Create the `hdip-secrets` Secret from
your secret manager first (see `secret.example.yaml`; never commit real values). SYNTHETIC DATA ONLY.

Hardening: restricted Pod Security, non-root, read-only root filesystem, dropped capabilities, no service-account
token, network policy (API in; DB/Kafka/Keycloak/DNS out; management port only from the `monitoring` namespace).
Validated in CI with kubeconform.
