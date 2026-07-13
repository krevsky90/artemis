ПРОБЛЕМА 1:
    при отправке Order в JMS (artemis):
    2026-07-10T18:27:02.286Z ERROR 1 --- [order-service] [nio-8081-exec-1] o.a.c.c.C.[.[.[/].[dispatcherServlet]    : Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed: org.springframework.jms.support.converter.MessageConversionException: Cannot convert object of type [com.krev.entity.Order] to JMS message. Supported message payloads are: String, byte array, Map<String,?>, Serializable object.] with root cause
    org.springframework.jms.support.converter.MessageConversionException: Cannot convert object of type [com.krev.entity.Order] to JMS message. Supported message payloads are: String, byte array, Map<String,?>, Serializable object.
РЕШЕНИЕ:
    Spring JMS пытается превратить его в JMS Message.
    По умолчанию JmsTemplate умеет следующие типы (см enum org.springframework.jms.support.converter.MessageType)
        String
        byte[]
        Map<String, ?>
        Serializable object

    Root cause: record Order должен быть Serializable!!
    НО если просто написать Order implements Serializable, то
    по умолчанию JMS ObjectMessage использует Java Serialization, это плохо, т.к.
        1) зависимость от версии Java
        2) невозможно нормально читать сообщения другими языками.
    ПОЭТОМУ отправляем JSON:
        Java Object --- Jackson --- JSON String --- TextMessage JMS --- Artemis
    Для этого:
        создать JmsConfig с MappingJackson2MessageConverter

КАК сохранить конфиги artemis в своем проекте и в дальшейшем artemis подхватывал именно их:
1) скопировать дефолтные конфиги артемиса в локальный проект:
    docker cp artemis:/var/lib/artemis-instance/etc ./broker/config
2.1) примонтировать локальную папку с конфигами в виде отдельного волюма
2.2) удалить локально те конфиги, к-ые не планируем менять, оставив лишь
    broker.xml, artemis-users.properties, artemis-roles.properties
2.3) примонтировать конкретные файлы из broker/config, а также docker-volume для данных
volumes:
  - ./broker/config/broker.xml:/var/lib/artemis-instance/etc/broker.xml
  - ./broker/config/artemis-users.properties:/var/lib/artemis-instance/etc/artemis-users.properties
  - ./broker/config/artemis-roles.properties:/var/lib/artemis-instance/etc/artemis-roles.properties
  - artemis-data:/var/lib/artemis-instance/data

NOTE:
Путь сообщения от order-service до inventory-service:
    order-service

    OrderEventCreated
     |
     |
    JmsTemplate
     |
     |
    Jackson converter
     |
     |
    TextMessage
     |
     |
    Artemis
     |
     |
    orders.queue
     |
     |
    inventory-service
     |
     |
    @JmsListener
     |
     |
    OrderEventCreated

Этап 3: добавить 5 консюмеров:
Спринг создает:
    DefaultMessageListenerContainer
        ↓
        Connection
        ↓
            Session1
            ↓
            Consumer1
            ↓
            Thread1


            Session2
            ↓
            Consumer2
            ↓
            Thread2

Путь сообщения, когда 1 консюмер:
    orders.queue  -> JMS Consumer -> Thread -> @JmsListener

Путь сообщение, когда 5 консюмеров (т.е. concurrency = 5)
    orders.queue
          │
     ┌────┴────┐
      Consumer1
        ...
      Consumer5
     └────┬────┘
          │
    Spring Listener Container

    То есть создается 5 независимых JMS Consumer.
    И каждый имеет
        1) собственную Session;
        2) собственный MessageConsumer;
        3) собственный поток.

В логах inventory-service:
    Thread=org.springframework.jms.JmsListenerEndpointContainer#0-3 finished order=8b9ba3ce-52c5-44e6-b973-0dccd4aacca5
    где #0 - номер JMS listener-a (т.к. над классом консюмера указан листенер)
        -3 - номер консюмера, отвечающего этому листенеру