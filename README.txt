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

ПРОБЛЕМА 2:
    переименовал БД постгреса в helm postres.yaml,
    в кубере сделал helm uninstall postgres -n infra,
    поставил заново инфру - а БД inventory_db не создалась. И по-прежнему имеется orders_db
РЕШЕНИЕ:
    после helm uninstall еще нужно дополнительно УДАЛИТЬ PVC!

=============================================

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

Этап 5. Ack from consumer
    Обычный flow:
        Получить сообщение -> Преобразовать JSON -> Вызвать @JmsListener
        -> Метод завершился без Exception -> Spring отправил ACK -> Artemis удалил сообщение

    Если произошло исключение:
        Получить сообщение -> Вызвать @JmsListener -> RuntimeException -> ACK НЕ отправлен
        -> Artemis считает сообщение необработанным -> Через некоторое время отправляет снова

    NOTE: ACK отправляется DefaultMessageListenerContainer-ом (или JmsListenerEndpointContainer в новых версиях Spring).
        т.е. по умолчанию НЕ моим кодом.

    Типы ACK-ов:
        1) AUTO_ACKNOWLEDGE - спринг решает сам
        2) CLIENT_ACKNOWLEDGE - программист решает message.acknowledge();
            Можно вызвать позже. Можно не вызвать.
            Можно обработать несколько сообщений и подтвердить их одной операцией.
        3) DUPS_OK_ACKNOWLEDGE
            ACK отправляется не сразу. Spring/JMS Provider может копить подтверждения.
            Это быстрее. Но возможны дубликаты после сбоя. Используется РЕДКО.
        4) SESSION_TRANSACTED
            Вообще нет ACK!
            Есть commit() или rollback()
            т.е. commit = ACK

    Механизм работы:
    т.к. ACK — это часть спецификации JMS.
        1) Spring вызывает JMS API.
        2) JMS-клиент отправляет ACK брокеру.
        3) Artemis реализует эту спецификацию и принимает ACK.

    ВОПРОС: В какой именно момент Spring отправляет ACK? До выхода из метода, после выхода из метода или после возврата управления в контейнер?
    ОТВЕТ:
        Spring отправляет ACK после того, как метод полностью завершился и управление вернулось обратно в контейнер JmsListenerEndpointContainer
        Детальная (примерная) схема:
            Получение сообщения (JmsListenerEndpointContainer -> MessageConsumer.receive())
            (здесь сообщение нах-ся в статусе In Delivery или Delivered)
                    │
            Десериализация (TextMessage -> MappingJackson2MessageConverter -> OrderCreatedEvent)
                    │
            Вызов @JmsListener (т.е. моего метода consume(event))
                    │
            МОЙ Метод полностью завершился
                    │
            Управление вернулось контейнеру Spring
                    │
            Контейнер принимает решение:
                    │
               ┌────┴────┐
            Успех     Exception
               │         │
            ACK     Recovery/Rollback
               │         │
            Удалить   Повторная
            сообщение доставка

        т.е. упрощенно:
            Message message = consumer.receive();
            Object payload = converter.fromMessage(message);
            try {
                listener.invoke(payload);
                acknowledge();  // или  session.commit();
            } catch (Exception e) {
                session.rollback();
            }

        NOTE: НЕЛЬЗЯ перехватывать и НЕ пробрасывать исключения в consume-методе. Иначе будет отправлен ACK!

Этап 5.2. Headers / message properties
    Заголовок / свойство	Для чего используется
    JMSMessageID	Уникальная идентификация сообщения, логирование. НЕ меняется при redelivery!
    JMSCorrelationID	Request/Reply, связь запроса и ответа
    JMSRedelivered	Определение повторной доставки (true/false)
    JMSXDeliveryCount	Логика повторных попыток, мониторинг, алерты
    JMSReplyTo	Асинхронный ответ на сообщение
    JMSPriority	Приоритетная обработка
    JMSExpiration	TTL сообщений
    JMSDestination	Диагностика и универсальные обработчики
    JMSTimestamp	Аудит и измерение задержек

Этап 6. DLQ settings
    Чтобы сделать ретрай консюмера 3 раза с интервалом 2 сек,
    нужно изменить broker.xml
        redelivery-delay = 2s
        max-delivery-attempts = 3
    затем скопировать локальный измененный файл на сервис артемиса
        docker cp ./broker/config/broker.xml artemis:/var/lib/artemis-instance/etc/broker.xml
    и рестартануть его:
        docker compose restart artemis

    NOTE: чтобы наглядно проверить, что идут ретраи, можно временно внедрить в consumer поле jakarta.jms.Message
        и взять у него проперти JMSXDeliveryCount
        Тогда в логах inventory-service увидим deliveryCount=1, потом = 2, потом =3.
        Пример:
        2026-07-13T18:38:10.432Z  INFO 1 --- [inventory-service] [ntContainer#0-7] com.krev.consumer.OrderConsumer          : deliveryCount=3

    NOTE: в отличие от кафка, в artemis есть DLQ по умолчанию. Имеет смысл рассмотреть 3 сценария:
        1) max-delivery-attempts=-1 — сообщение бесконечно переотправляется и никогда не попадает в DLQ. растет JMSXDeliveryCount.
            используют для каких-то супер важных сообщений.
            ОПАСНО, т.к. если в очереди poison message (например, с throw new RuntimeException()), то вся очередь будет бесконечно ждать.
            ИМЕННО из-за poison message и придумали DLQ!

            Пример настройки для конкретного паттерна очередей (в broker.xml):
                <address-setting match="orders.#">
                    <max-delivery-attempts>-1</max-delivery-attempts>
                </address-setting>
        2) max-delivery-attempts=3 — сообщение после трех ошибок уходит в DLQ. Это классический сценарий, к-ый я реализовал
        3) Большой redelivery-delay, например 30000 мс. Тогда станет заметно, что очередь не "долбит" Consumer непрерывно, а выдерживает паузу между попытками.

Этап 7. Transactions
Есть JMS transactions - чтобы в одной JMS session атомарно:
    1) читать сообщения из артемиса
    2) как-то обрабатывать из java-кодом
    3) отправлять сообщения в артемис
    Применение: полезно для кейса "прочитал и отправил дальше".
        Т.к. если, например, отправка упадет, то и факт чтения останется без ACK-a (а точнее, commit-a)
    Как настраивается: см inventory-service/src/main/java/com/krev/config/JmsConfig.java # jmsListenerContainerFactory
        строкой factory.setSessionTransacted(true);

Есть транзакционности других систем (БД, Кафка и пр).

ПРОБЛЕМА: JMS session и транзакция, например, Postgres НИКАК НЕ СИНХРОНИЗИРОВАНЫ!
    поэтому если консюмер читает сообщение и сохранение в БД падает,
    то на JMS сессию это не влияет, и может быть отправлен ACK в очередь.
РЕШЕНИЕ: идемпотентный консюмер (Inbox pattern), т.е. защита от повторной обработки входящих сообщений;
        + транзакционность всех операций с одной и той же БД.
    Пример: см inventory-service/src/main/java/com/krev/service/OrderProcessor.java
    т.е.
        1) читаем сообщение
        2) пытаемся сохранить event_id в таблицу. Если сохранение такого id уже было, то событие уже обработано, ничего не делаем. Иначе - сохраняем.
        3) сохраняем данные из event-а в inventory таблицу. Или как-то по-другому влияем на inventory таблицу пришедшим событием.

Этап 8. Topic vs Queue
info:
!!! https://chat.qwen.ai/c/fdfff862-50d0-49ce-b4c3-16a456904d7f
https://chatgpt.com/g/g-p-69de2569c3f481918b01d49dddd12f4c-swe/c/6a611648-b4e4-83ed-9dc7-ded84f7d0a5b

Если Queue, то сообщение получает один из консюмеров этой очереди.
Если Topic, то каждый подписчик получает свою копию сообщения.

Что где используется:
	Queue - когда есть одна задача, которую должен выполнить один исполнитель (Кто первый взял — тот сделал)
	Topic - когда есть событие: "что-то произошло" и много заинтересованных систем.

NOTE: сообщение не хранится в Topic. Topic — это скорее "точка маршрутизации".

Определения:
	Durable подписка - создается очередь, связанная с топиком. Если консюмер отключается, очередь копит соообщения и ждет, пока он подключится. Тогда он прочитает, сообщения удалятся из очереди.
	Shared подписка - НЕ указывается clientId, из очереди могут читать несколько консюмеров. Используется load balancing. Чтение масштабируется.

Как задать создание ConnectionFactory:
1) через application.yaml
	Пример:
		spring:
		  jms:
			pub-sub-domain: true # Обязательно для топиков
			listener:
			  session:
				transacted: true	# включаем транзакционность JMS
			  # may be client-id: inventory-service-1
			subscription:
			  durable: true # Включаем durable режим

2) Через @Bean в Java Config-e (класс JmsConfig)
	Пример:
		@Bean
		public DefaultJmsListenerContainerFactory topicListenerFactory(
				ConnectionFactory connectionFactory,
				MessageConverter converter
		) {
			DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

			factory.setConnectionFactory(connectionFactory);
			factory.setMessageConverter(converter);
			// factory.setPubSubDomain(true); // Можно оставить, но spring.jms.pub-sub-domain=true в YAML уже это делает
			factory.setConcurrency("3-6");

			factory.setSubscriptionDurable(true);

			factory.setSessionTransacted(true);

			return factory;
		}

NOTE: конфигурация в @Bean перетирает/важнее конфигурации из application.yaml!

Best practice: более гибко - задавать конфигурацию в @Bean и потом указывать название бина в консюмере в containerFactory
	Пример:
		@JmsListener(destination = "${messaging.topics.orders}",
            subscription = "${messaging.subscriptions.inventory}",
            containerFactory = "topicListenerFactory")
    public void consume(OrderCreatedEvent event, Message message) { ... }

	потому что теор-ски один и тот же сервис может слушать и топики (нужна фабрика с PubSubDomain = true), и обычные очереди (т.е. PubSubDomain = false)
	Аналогичные бины для создания разных фабрик задаются и в сервисе-продюсере.


ДОПУСТИМ:
	топик и очереди заданы в broker.xml
		broker.xml
		<addresses>
          <address name="orders.topic">
            <multicast>
               <queue name="inventory-subscription"/>
            </multicast>
          </address>
		   ...
		 </addresses>

Типы подписок:
1. (старая, JMS 1.1) Classic (Non-shared) durable
	Очередь имеет свойства, указанные в определении durable.
	Читать из этой очереди может только один подписчик. У него должен быть абсолютно уникальным в рамках всего брокера Artemis.clientId (указан в application.yaml сервиса).
	ЕСЛИ, например, в сервисе создать несколько консюмеров, и у них будут одинаковые clientId, то вторая копия "выбьет" первую из брокера, а будет ошибка вида:
			"message":"Could not refresh JMS Connection for destination 'orders.topic' - ...
			Cause: clientID=inventory-service was already set into another connection

	ПРОБЛЕМА 1: переименовали clientId - очередь осталась висеть навсегда
	ПРОБЛЕМА 2: чтобы масштабировать приложение, для каждого инстанса сервиса нужен уникальный clientId. Типа
		spring:
		 jms:
		   listener:
			 client-id: inventory-service-${random.uuid} # Или ${HOSTNAME} в Kubernetes
	ПРОБЛЕМА 3: если задать параметры для ConnectionFactory не в application.yaml, а в JmsConfig в виде бина, то ошибка
		setClientID call not supported on proxy for shared Connection. Set the 'clientId' property on the SingleConnectionFactory instead
		хз, как обойти это ограничение, поэтому ограничился application.yaml

	Код:
		application.yaml
			jms:
			  listener:
				session:
				  transacted: true
			  pub-sub-domain: true
			  subscription-durable: true
			  client-id: inventory-service

		Консюмер:
			@Component
			@Slf4j
			public class TemporaryNotificationConsumer {
				@JmsListener(destination = "${messaging.topics.orders}",
						subscription = "${messaging.subscriptions.notification}")
				public void consume(OrderCreatedEvent event) { ... }

	ИТОГО: Classic Durable consumer используют для легаси систем, где осталось JMS 1.1. Или когда очень важно знать, кто именно подключился и считал сообщение.

2. Shared durable
	Очередь имеет свойства, указанные в определении durable.
	clientId задавать НЕ нужно.
	Потому что в JMS 2.0 для Shared Durable client-id не обязателен, так как брокер идентифицирует подписку только по её имени.
	Тогда консюмеры могут по очереди (load balancing) читать сообщения из очереди топика и затем (после ack) сообщение удаляется из очереди топика.
	С точки зрения кода - см "Classic (Non-shared) durable", НО НУЖНО
	а) удалить clientId
	б) просеттить subscription-shared
	Тогда Spring Boot автоматически использует JMS 2.0 Shared Durable Consumer.
	NOTE: если не сделать б), то Spring будет юзать JMS 1.1, и требовать clientId!

	В JmsConfig создать
		@Bean
		public DefaultJmsListenerContainerFactory topicListenerFactory(
				ConnectionFactory connectionFactory,
				MessageConverter converter
		) {
			DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

			factory.setConnectionFactory(connectionFactory);
			factory.setMessageConverter(converter);
			factory.setPubSubDomain(true);
			factory.setSubscriptionDurable(true); // Durable subscription
			factory.setSubscriptionShared(true);  // Shared subscription
	//        factory.setClientId("inventory-service");	// do NOT set clientId!

			// turn on JMS transactions
			factory.setSessionTransacted(true);

			return factory;
		}

	Удалить из application.yml секцию spring.jms

	Указать в консюмерах сервиса containerFactory = "topicListenerFactory". Т.е.
		@JmsListener(destination = "${messaging.topics.orders}",
            subscription = "${messaging.subscriptions.inventory}",
            containerFactory = "topicListenerFactory")

	NOTE: в JmsProperties НЕТ свойства subscription-shared, поэтому его можно задать только через setSubscriptionShared в @Bean !

	ИТОГО: На данный момент это ОЧЕНЬ ПОПУЛЯРНЫЙ И МАСШТАБИРУЕМЫЙ подход! По сути напоминает работу с кафкой, только та не удаляет сообщение из очереди после прочтения.

3. Classic (Non-shared) volatile (Non-durable):
    Т.к. очередь non-durable, то:
        1) она создается автоматически при подключении консюмера к топику, ее имя - автосгенеренный UUID
        2) при отлючении консюмера (разрыв TCP соединения очередь автоматически УДАЛЯЕТСЯ, сообщения в ней УДАЛЯЮТСЯ
        3) Если запустить 3 копии сервиса, каждая создаст свою собственную временную очередь.
            Каждая копия получит полную копию всех сообщений (Fan-out / Broadcast).
            Load balancing (разделение нагрузки) здесь не работает.

    Код:
        В broker.xml
            a) указываем только названия топика, а временные очереди создадутся сами
                <address name="orders.topic">
                    <multicast/> <!-- Тип маршрутизации: Multicast (Topic) -->
                </address>
            b) убедиться, что <auto-create-queues>true</auto-create-queues>

        В консюмере указываем ТОЛЬКО топик:
            @JmsListener(destination = "${messaging.topics.orders}")

        В application.yml или JmsConfig-е указываем лишь pub-sub-domain: true
            т.е. subscription-durable, subscription-shared, client-id - удаляем!

    ВОПРОС: почему, несмотря на настройки
        <auto-create-queues>false</auto-create-queues>
        <auto-create-addresses>false</auto-create-addresses>
        в broker.xml,
        Артемис все равно создает новые очереди с UUID-шным именем для консюмеров?
    ОТВЕТ: потому что настройки по auto-create относятся к CORE очередям и адресам (топикам).
        если внимательно посмотреть на парентовый тэг, то это как раз <core>
        Этот же протокол указан для Producer-а в Web Console Артемиса.
        То есть auto-create = false запрешает создавать топики/очереди, в которые будет писать producer!
        Но НЕ запрещает создавать non-durable очереди, ИЗ к-ых будут читать консюмеры!



КАК сделать Topic в Artemis:
Способ 1 (правильный):
	1) В broker.xml добавим address:
		<addresses>
			<address name="orders.topic">
				<multicast>
					<queue name="inventory.subscription"/>
					<queue name="notification.subscription"/>
					<queue name="analytics.subscription"/>
				</multicast>
			</address>
		</addresses>

	2) В JmsConfig консюмера создать бин DefaultJmsListenerContainerFactory topicListenerFactory:
		"Настройка": setPubSubDomain(true) | "Зачем нужна": для Работа с Topics | "Что будет без неё": Будет работать с Queue (point-to-point)
		"Настройка": setSubscriptionDurable(true) | "Зачем нужна": Включение durable режима | "Что будет без неё": Consumer будет не-durable (сообщения теряются при остановке)
		"Настройка": setSubscriptionShared(true) | "Зачем нужна": Включение shared режима | "Что будет без неё": Consumer будет не-shared.
		"Настройка": setClientId(clientId) | "Зачем нужна": Идентификация клиента | "Что будет без неё": Ошибка! Durable subscription требует client ID
		"Настройка": subscription = "..." | "Зачем нужна": Имя подписки | "Что будет без неё": Подписка будет без имени (не durable)

		NOTE: сочетание clientId + subscription д б УНИКАЛЬНО!

	3) В консюмерах написать
		@JmsListener(destination = "${messaging.topics.orders}",	//это имя топика из broker.xml
            subscription = "${messaging.subscriptions.inventory}",	// это queue name из broker.xml
            containerFactory = "topicListenerFactory")				// это имя фабрики из JmsConfig

	4.1) На стороне продюсера:
		кастомизировать бин JmsTemplate topicJmsTemplate, проставив template.setPubSubDomain(true);
	4.2) заюзать бин и топик
		@Value("${messaging.topics.orders}")
		private String topicName;

		public OrderProducer(@Qualifier("topicJmsTemplate") JmsTemplate jmsTemplate) {
			this.jmsTemplate = jmsTemplate;
		}

	NOTE:
		Topic (Address с multicast routing) - это источник сообщений. Producer отправляет сюда: jmsTemplate.convertAndSend("orders.topic", order);
		Subscription (подписка) - каждый подписчик получает свою очередь. например, <queue name="inventory.subscription"/>

	имя subscription уникально ТОЛЬКО в пределах topic-a!

Способ 2 (неправильный, костыльный):
	Если в Artemis включены настройки по умолчанию:
	<address-settings>
		<address-setting match="#">
			<auto-create-addresses>true</auto-create-addresses>
			<auto-create-queues>true</auto-create-queues>
		</address-setting>
	</address-settings>

	то Spring сам создаст топик и очередь-подписку (durable subscription), если в консюмере написано:
	@JmsListener(
		// внимание!! т.к. создается топик и создается подписка-очередь, то указываем и то, и другое (в отличие от консюмера из способа 1)
		destination = "orders.topic",
		subscription = "inventory-subscription"
	)
	public void consume(Order order) {}

	NOTE: если не включить auto-create-addresses = true, то будет ошибка Destination orders.topic does not exist или AMQ229017: Address does not exist

NOTE: несмотря на то, что listener подключается к inventory-subscription, это не самостоятельный Topic, а multicast-очередь, принадлежащая адресу orders.topic.
Именно поэтому такая схема работает в Artemis. Это одна из особенностей реализации JMS в Artemis

-----------------
КАК создать factory для JMS template продюсера и JMS listener консюмера?
Если все просто, и сервис содержит единственную фабрику (например, для отправки в очередь ИЛИ топик), то можно просто задать в application.yaml
spring:
  jms:
    pub-sub-domain: true # true - for topic, false - for queue
    listener:
      session:
        transacted: true # true - to use JMS transaction

Если же требуется более одной фабрики (например, чтобы уметь принимать/отправлять сообщения И в очередь, И в топик),
то нужно писать JmsConfig явно и использовать алиас фабрики:
	для консюмера: @JmsListener(destination = "...", containerFactory = "topicListenerFactory")
	для продюсера используем @Qualifier:
		private final JmsTemplate topicJmsTemplate;

		public OrderProducer(@Qualifier("topicJmsTemplate") JmsTemplate topicJmsTemplate) {
			this.topicJmsTemplate = topicJmsTemplate;
		}
-----------------

NOTE: в JMS Topic подписчик обычно должен быть durable, если он должен получать сообщения, даже когда был выключен (чтобы не терять сообщения)

Таким образом, в Artemis/JMS обычно:
	Topic
	  |
	Subscription queues
	  |
	Consumers

И @JmsListener почти всегда висит именно на конечной очереди.
Это важное отличие от Kafka, где consumer group сама является механизмом подписки. В JMS/Artemis эта логика больше вынесена в broker