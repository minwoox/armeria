import { History, HistoryLocation } from '@reach/router';
import { Carousel, Button, Tooltip, Typography } from 'antd';
import React from 'react';
import { graphql, Link, useStaticQuery } from 'gatsby';
import Img from 'gatsby-image';
import { OutboundLink } from 'gatsby-plugin-google-analytics';

import Blockquote from '../components/blockquote';
import BrowserMockup from '../components/browser-mockup';
import CodeBlock from '../components/code-block';
import Emoji from '../components/emoji';
import Logo from '../components/logo';
import { Highlight, Marketing, MarketingBlock } from '../components/marketing';
import NoWrap from '../components/nowrap';
import ProjectBadge from '../components/project-badge';
import BaseLayout from '../layouts/base';

import styles from './index.module.less';

const { Title, Paragraph } = Typography;

const IndexPage: React.FC<{
  history?: History;
  location: HistoryLocation;
}> = props => {
  const data = useStaticQuery(graphql`
    query {
      site {
        siteMetadata {
          slackInviteUrl
        }
      }

      docServiceImages: allFile(
        filter: { relativePath: { glob: "docservice-carousel-*.png" } }
        sort: { fields: relativePath, order: ASC }
      ) {
        nodes {
          childImageSharp {
            fluid {
              ...GatsbyImageSharpFluid
            }
          }
        }
      }

      integrationDiagram: file(relativePath: { eq: "integration.svg" }) {
        publicURL
      }
    }
  `);

  function renderGetStartedButtons(responsive: boolean) {
    const className = `${styles.getStartedButton} ${
      responsive ? styles.responsive : ''
    }`;
    return (
      <>
        <Link to="/docs" className={className}>
          <Button type="primary" size="large">
            Learn more
            <Emoji emoji="✨" label="sparkles" />
          </Button>
        </Link>
        <Link to="/community" className={className}>
          <Button size="large">
            Community
            <Emoji emoji="👋" label="waving hand" />
          </Button>
        </Link>
      </>
    );
  }

  return (
    <BaseLayout
      {...props}
      pageTitle="Armeria &ndash; An all-round microservice framework"
    >
      <Marketing className={styles.slogan}>
        <MarketingBlock noreveal>
          <Logo
            className={styles.sloganBackgroundImage}
            width="768px"
            height="768px"
            primaryColor="rgba(255, 255, 255, 1.0)"
            secondaryColor="rgba(255, 255, 255, 0.55)"
            tertiaryColor="transparent"
            notext
          />
          <Title level={1}>
            Build a reactive microservice{' '}
            <NoWrap>
              <Highlight>
                at <u>your</u> pace
              </Highlight>
              ,
            </NoWrap>{' '}
            <NoWrap>not theirs.</NoWrap>
          </Title>
          <Paragraph>
            <em>Armeria</em> is an all-round microservice framework that lets
            you build any type of microservices leveraging your favorite
            technologies, including gRPC, Thrift, Kotlin, Retrofit, Reactive
            Streams, Spring Boot and Dropwizard.
          </Paragraph>
          <Paragraph className={styles.indented}>
            &ldquo; Brought to you by the team led by the founder of{' '}
            <Tooltip title="The most popular non-blocking I/O client-server framework in Java ecosystem">
              <OutboundLink href="https://netty.io/">Netty</OutboundLink>
            </Tooltip>{' '}
            &rdquo;
          </Paragraph>
          <div>{renderGetStartedButtons(true)}</div>
          <div className={styles.badges}>
            <div>
              <ProjectBadge url="https://img.shields.io/github/stars/line/armeria.svg?style=social" />
              <ProjectBadge url="https://img.shields.io/twitter/follow/armeria_project.svg?label=Follow" />
              <ProjectBadge
                url={`https://img.shields.io/badge/chat-on%20Slack-brightgreen.svg?style=social&logo=slack&link=${encodeURIComponent(
                  data.site.siteMetadata.slackInviteUrl,
                )}`}
              />
            </div>
            <div>
              <ProjectBadge
                url={`https://img.shields.io/maven-central/v/com.linecorp.armeria/armeria.svg?link=${encodeURIComponent(
                  'https://search.maven.org/search?q=g:com.linecorp.armeria%20AND%20a:armeria',
                )}`}
              />
              <ProjectBadge
                url={`https://img.shields.io/github/commit-activity/m/line/armeria.svg?link=${encodeURIComponent(
                  'https://github.com/line/armeria/pulse',
                )}`}
              />
            </div>
          </div>
        </MarketingBlock>
      </Marketing>
      <Marketing>
        <MarketingBlock>
          <Title level={1}>
            gRPC, Thrift, REST, files? <NoWrap>You name it.</NoWrap>{' '}
            <Highlight nowrap>We serve them all.</Highlight>
          </Title>
          <Paragraph>
            Let&apos;s embrace the reality &mdash; we almost always have to deal
            with more than one protocol. It was once Thrift, today it&apos;s
            gRPC, and REST never gets old. At the same time, you sometimes have
            to handle health check requests from a load balancer or even serve
            some static files.
          </Paragraph>
          <Paragraph>
            Armeria is capable of running services of different protocols, all
            in a single port. Waste no more setting up forward proxy or sidecar.
            Worry no more about operational complexity and points of failure.
            Imagine you don&apos;t need to set anything up while you migrate
            from one protocol to another.
          </Paragraph>
        </MarketingBlock>
        <MarketingBlock>
          <CodeBlock language="java">
            {`
            Server
              .builder()
              .service(
                "/hello",
                 (ctx, req) -> HttpResponse.of("Hello!"))
              .service(GrpcService
                .builder()
                .addService(myGrpcServiceImpl)
                .build())
              .service(
                "/api/thrift", 
                ThriftService.of(myThriftServiceImpl));
              .service(
                "prefix:/files",
                FileService.of(new File("/var/www")))
              .service(
                "/monitor/l7check",
                HealthCheckService.of())
              .build()
              .start();
            `}
          </CodeBlock>
        </MarketingBlock>
      </Marketing>
      <Marketing reverse>
        <MarketingBlock>
          <Title level={1}>
            <Highlight>Supercharge your RPC</Highlight> with documentation
            service
          </Title>
          <Paragraph>
            RPC protocols were often more difficult to work with than RESTful
            API. How would you make an ad-hoc call to your gRPC service when you
            can&apos;t find the right <code>.proto</code> file? How would you
            help your colleague reproduce your problem by making the same call?
          </Paragraph>
          <Paragraph>
            Armeria&apos;s <em>documentation service</em> allows you to browse
            the complete list of services and their documentation from a web
            browser. You can also make a real RPC call in human friendly JSON
            format and share the URL of the call with your colleague. Who said
            RPC is difficult to work with? <Emoji emoji="😆" label="laughing" />
          </Paragraph>
        </MarketingBlock>
        <MarketingBlock>
          <Carousel
            className={styles.docServiceCarousel}
            dots={false}
            autoplay
            autoplaySpeed={5000}
          >
            {data.docServiceImages.nodes.map((e: any) => (
              <BrowserMockup key={e.childImageSharp.fluid.src}>
                <Img fluid={e.childImageSharp.fluid} />
              </BrowserMockup>
            ))}
          </Carousel>
        </MarketingBlock>
      </Marketing>
      <Marketing className={`${styles.testimonial} ${styles.testimonialOdd}`}>
        <MarketingBlock>
          <Blockquote
            author={
              <OutboundLink href="https://github.com/andrewoma">
                Andrew O&apos;Malley
              </OutboundLink>
            }
            from={
              <OutboundLink href="https://en.wikipedia.org/wiki/Afterpay">
                Afterpay
              </OutboundLink>
            }
            bgColor1="white"
            bgColor2="#f7f7f7"
          >
            Armeria has eased our adoption of gRPC at Afterpay. Serving gRPC,
            documentation, health checks and metrics on a single port (with or
            without TLS) gives us the functionality of a sidecar like Envoy in a
            single process. Automated serving of API documentation, complete
            with sample payloads, allows simple ad-hoc gRPC requests straight
            from the browser. Distributed tracing is trivial to configure using
            the built-in Zipkin decorators. Logging request bodies, performing
            retries or circuit breaking are all built-in. The friendly devs
            rapidly respond to issues and feature requests. Armeria is becoming
            the default stack for gRPC at Afterpay.
          </Blockquote>
        </MarketingBlock>
      </Marketing>
      <Marketing>
        <MarketingBlock>
          <Title level={1}>
            Fuel your request pipeline with{' '}
            <Highlight>reusable components</Highlight>
          </Title>
          <Paragraph>
            There are so many cross-cutting concerns you need to address when
            building a microservice &mdash; metrics, distributed tracing, load
            balancing, authentication, rate-limiting, circuit breakers,
            automatic retries, etc.
          </Paragraph>
          <Paragraph>
            Armeria solves your problems by providing various reusable
            components called &lsquo;decorators&rsquo; in a clean, consistent,
            flexible and user-friendly API. You can also write a custom
            decorator if ours doesn&apos;t work for your use case.
          </Paragraph>
        </MarketingBlock>
        <MarketingBlock>
          <CodeBlock language="java">
            {`
            Server
              .builder()
              .service((ctx, req) -> ...);
              .decorator(
                MetricCollectingService.newDecorator(...))
              .decorator(
                BraveService.newDecorator(...))
              .decorator(
                AuthService.newDecorator(...))
              .decorator(
                ThrottlingService.newDecorator(...))
              .build()
              .start();
            `}
          </CodeBlock>
        </MarketingBlock>
      </Marketing>
      <Marketing reverse>
        <MarketingBlock>
          <Title level={1}>
            <Highlight>Integrate seamlessly</Highlight> with your favorite
            frameworks &amp; languages
          </Title>
          <Paragraph>
            Wanna try some cool technology while using Spring Boot or
            Dropwizard? That&apos;s absolutely fine. Armeria integrates with
            them so smoothly that it&apos;s not like any other that claims so.
            It serves gRPC or Thrift requests at high efficiency while leaving
            other requests to Spring MVC, WebFlux or Dropwizard with minimal
            changes only.
          </Paragraph>
          <Paragraph>
            Got a legacy webapp you must keep until you migrate off? No problem!
            Armeria can serve any legacy webapps that run on top of Tomcat or
            Jetty until you finish your migration. Adios, maintenance hell!{' '}
            <Emoji emoji="🥂" label="clinking glasses" />
          </Paragraph>
        </MarketingBlock>
        <MarketingBlock>
          <img
            src={data.integrationDiagram.publicURL}
            alt="A diagram that shows the integrations Armeria provides"
            style={{ width: '100%' }}
          />
        </MarketingBlock>
      </Marketing>
      <Marketing>
        <MarketingBlock>
          <Title level={1}>
            Flexible <Highlight>service discovery</Highlight> and client-side{' '}
            <Highlight>load-balancing</Highlight>
          </Title>
          <Paragraph>
            Armeria provides various service discovery mechanisms, from
            Kubernetes-style DNS records to ZooKeeper data nodes. Send periodic
            or long-polling pings to exclude unhealthy endpoints autonomously.
            Choose the best load-balancing strategy for you &mdash; weighted
            round-robin, sticky-session. You can even write a custom service
            discovery or load-balancing strategy.
          </Paragraph>
        </MarketingBlock>
        <MarketingBlock>
          <CodeBlock language="java">
            {`
            EndpointGroup group =
              DnsAddressEndpointGroup.of(
                "k8s.default.svc.cluster.local.");

            EndpointGroup healthyGroup =
              HealthCheckedEndpointGroup.of(
                group,
                "/monitor/l7check");

            WebClient client =
              WebClient.of("h2c", healthyGroup);

            client.get("/path");
            `}
          </CodeBlock>
        </MarketingBlock>
      </Marketing>
      <Marketing className={`${styles.testimonial} ${styles.testimonialEven}`}>
        <MarketingBlock>
          <Blockquote
            author={
              <OutboundLink href="https://github.com/ikhoon">
                Ikhoon Um
              </OutboundLink>
            }
            from={
              <OutboundLink href="https://en.wikipedia.org/wiki/Afterpay">
                LINE Corporation
              </OutboundLink>
            }
            bgColor1="rgba(255, 255, 255, 0.75)"
            bgColor2="#f7f7f7"
          >
            크 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으
            으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으
            으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으
            으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으
            으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으
            으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으
            으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으
            으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으
            으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으
            으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으
            으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 으 윽
          </Blockquote>
        </MarketingBlock>
      </Marketing>
      <Marketing className={styles.getStarted}>
        <MarketingBlock>
          <Title level={3}>
            Build your service with
            <Logo
              width="10.5rem"
              textColor="#ff0089"
              style={{
                position: 'relative',
                top: '-0.18rem',
                marginLeft: '0.3rem',
                marginRight: '0.35rem',
              }}
              label="Armeria"
            />
            today!
          </Title>
          {renderGetStartedButtons(false)}
        </MarketingBlock>
      </Marketing>
    </BaseLayout>
  );
};

export default IndexPage;
