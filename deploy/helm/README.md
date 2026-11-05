# Helm chart

`deploy/helm/hdip` is the Helm equivalent of `deploy/k8s` (same hardening: restricted Pod Security, non-root, read-only root
filesystem, dropped capabilities, no service-account token, network policy). Postgres, Kafka and Keycloak are provided by the
platform (`values.yaml: config`). SYNTHETIC DATA ONLY.

```
helm lint deploy/helm/hdip
helm template hdip deploy/helm/hdip | kubeconform -strict -summary -kubernetes-version 1.30.0 -
helm install hdip deploy/helm/hdip -n hdip --create-namespace
```

## Secrets (never in values or Git)

The pod reads `DB_PASSWORD`, `PHI_ENCRYPTION_KEYS`, `PHI_ACTIVE_KEY_ID`, `PHI_HMAC_KEY` from the Secret named by `existingSecret`.
Create it with one of:

- External Secrets Operator: set `externalSecret.enabled=true` (chart renders an `ExternalSecret`), or apply `examples/externalsecret.yaml`.
- Sealed Secrets: `kubeseal` your Secret and commit only the output; `examples/sealedsecret.yaml` shows the shape with placeholders.

## GitOps

`deploy/argocd/application.yaml` is an Argo CD `Application` pointing at this chart. CI validates the chart and all examples
(`helm lint`, `helm template | kubeconform`, kubeconform on examples with the CRDs catalog).
