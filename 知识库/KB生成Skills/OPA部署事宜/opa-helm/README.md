# OPA Service Helm Chart

This chart deploys OPA as a single Kubernetes container and can configure OPA to
periodically download a policy bundle from a fixed GitLab Generic Package
Registry URL. Policy updates are activated by OPA without a Helm deployment.

## GitLab bundle configuration

The current producer project publishes the bundle at this stable HTTPS URL:

```text
https://gitspace.wuxibiologics.com/api/v4/projects/1355/packages/generic/opa-policy-bundle/latest/bundle.tar.gz
```

The non-secret connection values are already configured in `values.yaml`:

```yaml
bundle:
  enabled: false
  serviceUrl: https://gitspace.wuxibiologics.com/api/v4/projects/1355/packages/generic/opa-policy-bundle/latest
  resource: bundle.tar.gz
  auth:
    username: gitlab+deploy-token-125
    deployToken: ""
  tls:
    allowInsecureTls: true
```

Do not commit a real username or token to a values file. The Deploy Token needs
the `read_package_registry` scope. The chart creates a Kubernetes Secret whose
mounted `basic-auth` file contains the HTTP Basic payload. The OPA ConfigMap and
container arguments contain only the path to that file.

Configure the real token as a protected and masked GitLab CI/CD variable:

- `OPA_DEPLOY_TOKEN`

Then enable the integration and inject only the Token at deployment time:

```sh
helm upgrade --install opa ./helm/opa-service \
  --set bundle.enabled=true \
  --set-string bundle.auth.deployToken="${OPA_DEPLOY_TOKEN}"
```

Keep command tracing disabled for the deployment command and do not print the
rendered Secret in CI logs. Kubernetes Secret values are encoded, not encrypted;
restrict Secret access with Kubernetes RBAC.

OPA polls for an updated bundle every 60 to 120 seconds by default. These values
can be changed with `bundle.polling.minDelaySeconds` and
`bundle.polling.maxDelaySeconds`. The Pod becomes Ready only after OPA reports a
healthy bundle through `/health?bundles`.

## TLS options

The current GitSpace endpoint requires certificate verification to be disabled,
so `bundle.tls.allowInsecureTls` is currently `true`. The connection still uses
HTTPS, but its server certificate is not verified.

Once the internal CA is available, disable insecure TLS:

```sh
--set bundle.tls.allowInsecureTls=false
```

To have the chart create and mount a CA Secret, define a GitLab file-type CI/CD
variable such as `OPA_BUNDLE_CA_FILE` and pass it without copying its contents
into Git:

```sh
--set-file bundle.tls.caCert="${OPA_BUNDLE_CA_FILE}"
```

Alternatively, reference an existing Secret:

```yaml
bundle:
  tls:
    existingSecret:
      name: company-internal-ca
      key: ca.crt
```

`bundle.tls.caCert` and `bundle.tls.existingSecret.name` are mutually exclusive.
When a custom CA is configured, OPA also retains the system CA trust set.
