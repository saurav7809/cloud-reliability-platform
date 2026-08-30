# syntax=docker/dockerfile:1
FROM golang:1.22 AS build
WORKDIR /app
COPY go.mod ./
COPY main.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /out/sample-service .

FROM gcr.io/distroless/static-debian12
COPY --from=build /out/sample-service /sample-service
EXPOSE 8080
ENTRYPOINT ["/sample-service"]
