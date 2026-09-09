FROM golang:1.23-alpine AS build

WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/ws-gateway ./cmd/ws-gateway

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/ws-gateway /ws-gateway
EXPOSE 8081
USER nonroot:nonroot
ENTRYPOINT ["/ws-gateway"]
